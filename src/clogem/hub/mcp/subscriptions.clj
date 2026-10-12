;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.subscriptions
  "The subscription feed behind `subscriptions/listen` (MCP 2026-07-28,
   docs/mcp-compliance.md).

   One feed per daemon listens on the bus for `:registry/changed` (→
   notifications/tools/list_changed and notifications/resources/list_changed)
   and `:resource/updated` (→ notifications/resources/updated for the URI)
   and fans each out to the open streams whose filter asked for it. A stream
   is transport-agnostic: the HTTP binding hands in a `send!` for SSE frames
   and a `close!`. Rules kept here: the acknowledgment is the first message of
   a stream and names the honored filter; every message carries the
   subscription id (the listen request's id) in `_meta`; a stream's buffer is
   bounded and drops (counted) rather than blocks the bus; quiet streams get
   an SSE comment as keep-alive; stopping the feed answers every open listen
   request with a `resultType \"complete\"` result before closing it."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clogem.hub.bus :as bus]
            [clogem.hub.log :as log]
            [clogem.sdk.event :as event]))

(def meta-subscription-id (keyword "io.modelcontextprotocol/subscriptionId"))
(def meta-server-info (keyword "io.modelcontextprotocol/serverInfo"))

(def sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

(defn sse-frame
  "An SSE event carrying one JSON-RPC message."
  [message]
  (str "data: " (json/generate-string message) "\n\n"))

(def keepalive-frame ": keepalive\n\n")

(defn notification
  "A JSON-RPC notification tagged with the subscription id."
  [method subscription-id params]
  {:jsonrpc "2.0" :method method
   :params (assoc params :_meta {meta-subscription-id subscription-id})})

(defn acknowledgment [subscription-id filter]
  (notification "notifications/subscriptions/acknowledged" subscription-id {:notifications filter}))

(defn closed-result
  "The graceful end of a stream: the listen request's own response."
  [subscription-id server-info]
  {:jsonrpc "2.0" :id subscription-id
   :result {:resultType "complete"
            :_meta {meta-subscription-id subscription-id
                    meta-server-info server-info}}})

;; ---------------------------------------------------------------------------
;; Streams

(defn- wants? [stream method uri]
  (let [f (:filter stream)]
    (case method
      "notifications/tools/list_changed" (true? (:toolsListChanged f))
      "notifications/resources/list_changed" (true? (:resourcesListChanged f))
      "notifications/resources/updated" (contains? (set (:resourceSubscriptions f)) uri)
      false)))

(defn- offer! [feed stream frame]
  (when-not (async/offer! (:ch stream) frame)
    ;; the buffer is a dropping buffer, so offer! always succeeds; kept for
    ;; the day it is made blocking
    (swap! (:stats feed) update :dropped inc)))

(defn- fan-out! [feed method uri]
  (doseq [stream (vals @(:streams feed))
          :when (wants? stream method uri)]
    (let [frame (sse-frame (notification method (:id stream) (if uri {:uri uri} {})))]
      (offer! feed stream frame))))

(defn- pump!
  "The stream's own thread: frames leave the bounded channel in order, so a
   slow client never blocks the bus or other streams."
  [feed {:keys [ch send! id] :as stream}]
  (async/thread
    (loop []
      (when-let [frame (async/<!! ch)]
        (try (send! frame)
             (swap! (:stats feed) update :sent inc)
             (catch Exception e
               (log/debug {:msg "listen stream send failed" :subscription id :error (ex-message e)})))
        (recur)))))

(defn open!
  "Register a stream and send its acknowledgment (always the first message).
   `stream` is {:id subscription-id :filter honored-filter :send! (fn [frame])
   :close! (fn [])}. Returns the stream handle."
  [feed {:keys [id filter send!] :as stream}]
  (let [buffer (get-in feed [:opts :buffer] 64)
        stream (assoc stream :ch (async/chan (async/dropping-buffer buffer)))
        stream (assoc stream :thread (pump! feed stream))]
    (send! (sse-frame (acknowledgment id filter)))
    (swap! (:streams feed) assoc (:key stream id) stream)
    (log/debug {:msg "listen stream opened" :subscription id :filter filter})
    stream))

(defn close!
  "Forget a stream (the client went away or the server ends it)."
  [feed stream]
  (when-let [s (get @(:streams feed) (:key stream (:id stream)))]
    (swap! (:streams feed) dissoc (:key stream (:id stream)))
    (async/close! (:ch s))
    (log/debug {:msg "listen stream closed" :subscription (:id s)})
    true))

;; ---------------------------------------------------------------------------
;; Feed lifecycle

(defn- keepalive-loop! [feed ms]
  (async/thread
    (loop []
      (let [[_ port] (async/alts!! [(:stop-ch feed) (async/timeout ms)])]
        (when-not (= port (:stop-ch feed))
          (doseq [stream (vals @(:streams feed))]
            (offer! feed stream keepalive-frame))
          (recur))))))

(defn start!
  "Start the feed over `bus`. Options: :keepalive-ms (default 15000),
   :buffer (default 64). Returns the feed."
  [bus {:keys [keepalive-ms buffer] :or {keepalive-ms 15000 buffer 64}}]
  (let [feed {:bus bus
              :streams (atom {})
              :stats (atom {:sent 0 :dropped 0})
              :stop-ch (async/chan)
              :opts {:keepalive-ms keepalive-ms :buffer buffer}}
        unsub-registry (bus/subscribe! bus :registry/changed
                                       (fn [_e]
                                         (fan-out! feed "notifications/tools/list_changed" nil)
                                         (fan-out! feed "notifications/resources/list_changed" nil))
                                       {:id [:subscriptions :registry/changed]})
        unsub-resource (bus/subscribe! bus event/resource-updated
                                       (fn [e]
                                         (when-let [uri (get-in e [:event/payload :uri])]
                                           (fan-out! feed "notifications/resources/updated" uri)))
                                       {:id [:subscriptions :resource/updated]})]
    (assoc feed :unsubscribe [unsub-registry unsub-resource]
           :keepalive (keepalive-loop! feed keepalive-ms))))

(defn stats [feed]
  (assoc @(:stats feed) :streams (count @(:streams feed))))

(defn stop!
  "End every stream gracefully (listen result, then close) and leave the bus."
  [{:keys [unsubscribe streams stop-ch] :as feed} server-info]
  (doseq [f unsubscribe] (try (f) (catch Exception _ nil)))
  (async/close! stop-ch)
  (doseq [stream (vals @streams)]
    (try ((:send! stream) (sse-frame (closed-result (:id stream) server-info)))
         (catch Exception _ nil))
    (close! feed stream)
    (try ((:close! stream)) (catch Exception _ nil)))
  nil)
