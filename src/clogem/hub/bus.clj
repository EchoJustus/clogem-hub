;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.bus
  "The in-process bus (PD-2): the only channel between modules inside the
   daemon besides the clogem.api facade.

   Events are broadcast facts. `publish!` validates the envelope
   (clogem.sdk.event) and hands it to one dispatcher thread, which copies it
   into the bounded channel of every subscriber of its :event/type. Each
   subscriber runs its handler on its own thread (`async/thread`, never a go
   block, so handlers may block on IO). A full subscriber channel drops the
   event for that subscriber only and reports a :bus/error; a slow handler
   never stalls the others.

   Commands are requests to a single owner. `register-owner!` claims a
   command keyword; `request!` enqueues `{:command … :reply ch}` on the
   owner's bounded channel and waits on a promise channel with a timeout.
   Owner handlers run on the owner's own thread and return the reply map
   ({:ok true :result …} or {:ok false :error {:type …}}).

   Failures (a throwing handler, an overflowing subscriber, a dropped
   publish) are published as :bus/error events with source :hub and logged.
   An error raised while handling a :bus/error event is only logged, so the
   bus cannot feed back into itself."
  (:require [clojure.core.async :as async]
            [clogem.sdk.event :as event]
            [clogem.hub.log :as log]))

(def defaults
  {:buffer 1024                ; the dispatcher's inbox
   :subscriber-buffer 256      ; per subscriber
   :owner-buffer 256           ; per command owner
   :publish-timeout-ms 1000    ; how long publish! waits for inbox room
   :request-timeout-ms 5000})  ; default budget of request! (enqueue + reply)

(def ^:private zero-stats
  {:published 0 :delivered 0 :dropped 0 :errors 0 :requests 0 :timeouts 0})

(declare publish!)

(defn- bump! [bus k] (swap! (:state bus) update-in [:stats k] inc))

(defn- error-event [payload]
  (event/event :hub :bus/error payload))

(defn- report-error!
  "Log a bus failure and, unless it was caused by a :bus/error event itself,
   publish it as one. `blocking?` false uses offer! (dispatcher thread)."
  [bus payload cause blocking?]
  (bump! bus :errors)
  (log/warn (assoc payload :msg "bus error"))
  (when-not (= :bus/error (:event/type cause))
    (if blocking?
      (publish! bus (error-event payload))
      (when-not (async/offer! (:in bus) (error-event payload))
        (bump! bus :dropped)))))

(defn- deliver! [bus e]
  (let [state @(:state bus)
        etype (:event/type e)]
    (doseq [id (get-in state [:by-type etype])
            :let [{:keys [ch]} (get-in state [:subscribers id])]
            :when ch]
      (if (async/offer! ch e)
        (bump! bus :delivered)
        (do (bump! bus :dropped)
            (report-error! bus {:reason :subscriber-overflow :subscriber id
                                :event/type etype :event/id (:event/id e)}
                           e false))))))

(defn- start-dispatcher! [bus]
  (async/thread
    (loop []
      (when-some [e (async/<!! (:in bus))]
        (try (deliver! bus e)
             (catch Throwable t
               (log/error {:msg "bus dispatcher failed" :error (ex-message t) :event/type (:event/type e)})))
        (recur)))
    :stopped))

(defn new-bus
  "Create and start a bus. `opts` override `defaults`."
  ([] (new-bus {}))
  ([opts]
   (let [opts (merge defaults opts)
         bus {:in (async/chan (:buffer opts))
              :state (atom {:subscribers {} :by-type {} :owners {} :stats zero-stats :open? true})
              :opts opts}]
     (assoc bus :dispatcher (start-dispatcher! bus)))))

(defn open? [bus] (boolean (:open? @(:state bus))))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publish an event envelope (see clogem.sdk.event/event). Throws on an
   invalid envelope. Waits up to :publish-timeout-ms for inbox room; a
   dropped event is logged and counted, never silently lost. Returns `e`."
  [bus e]
  (when-not (event/valid? e)
    (throw (ex-info "invalid event envelope" {:type :invalid-event :problems (event/explain e)})))
  (let [{:keys [in opts]} bus]
    (if-not (open? bus)
      (do (bump! bus :dropped)
          (log/warn {:msg "publish on a stopped bus" :event/type (:event/type e)}))
      (let [t (async/timeout (:publish-timeout-ms opts))
            [v port] (async/alts!! [[in e] t])]
        (cond
          (= port t) (do (bump! bus :dropped)
                         (log/warn {:msg "bus inbox full, event dropped" :event/type (:event/type e) :event/id (:event/id e)}))
          (false? v) (do (bump! bus :dropped)
                         (log/warn {:msg "publish on a closed bus" :event/type (:event/type e)}))
          :else (bump! bus :published))))
    e))

(defn- start-subscriber! [bus {:keys [id ch handler]}]
  (async/thread
    (loop []
      (when-some [e (async/<!! ch)]
        (try (handler e)
             (catch Throwable t
               (report-error! bus {:reason :handler-failed :subscriber id
                                   :event/type (:event/type e) :event/id (:event/id e)
                                   :message (ex-message t)}
                              e true)))
        (recur)))
    :stopped))

(defn subscribe!
  "Call `(handler event)` on a dedicated thread for every event of
   `event-type` (a qualified keyword). Returns an unsubscribe function.
   `opts` may set :buffer (default :subscriber-buffer) and :id."
  ([bus event-type handler] (subscribe! bus event-type handler {}))
  ([bus event-type handler opts]
   (when-not (qualified-keyword? event-type)
     (throw (ex-info "event type must be a qualified keyword" {:type :invalid-event-type :event/type event-type})))
   (when-not (fn? handler)
     (throw (ex-info "handler must be a function" {:type :invalid-handler})))
   (let [id (or (:id opts) (random-uuid))
         ch (async/chan (or (:buffer opts) (get-in bus [:opts :subscriber-buffer])))
         sub {:id id :event/type event-type :ch ch :handler handler}
         sub (assoc sub :thread (start-subscriber! bus sub))]
     (swap! (:state bus) (fn [s] (-> s (assoc-in [:subscribers id] sub)
                                     (update-in [:by-type event-type] (fnil conj #{}) id))))
     (fn unsubscribe []
       (when-let [{:keys [ch]} (get-in @(:state bus) [:subscribers id])]
         (swap! (:state bus) (fn [s] (-> s (update :subscribers dissoc id)
                                         (update-in [:by-type event-type] disj id))))
         (async/close! ch)
         true)))))

;; ---------------------------------------------------------------------------
;; Commands

(defn- start-owner! [bus command {:keys [ch handler]}]
  (async/thread
    (loop []
      (when-some [{:keys [request reply]} (async/<!! ch)]
        (let [r (try (handler request)
                     (catch Throwable t
                       (report-error! bus {:reason :handler-failed :command command :message (ex-message t)}
                                      nil true)
                       {:ok false :error {:type :handler-failed :command command :message (ex-message t)}}))]
          (async/put! reply (if (map? r) r {:ok false :error {:type :bad-reply :command command}})))
        (recur)))
    :stopped))

(defn register-owner!
  "Make `handler` (fn [request] reply-map) the single owner of `command`.
   Throws when the command already has an owner. Returns an unregister fn."
  ([bus command handler] (register-owner! bus command handler {}))
  ([bus command handler opts]
   (when-not (keyword? command)
     (throw (ex-info "command must be a keyword" {:type :invalid-command :command command})))
   (let [ch (async/chan (or (:buffer opts) (get-in bus [:opts :owner-buffer])))
         owner {:ch ch :handler handler}
         owner (assoc owner :thread (start-owner! bus command owner))
         claimed (swap! (:state bus) (fn [s] (if (get-in s [:owners command]) s (assoc-in s [:owners command] owner))))]
     (when-not (identical? owner (get-in claimed [:owners command]))
       (async/close! ch)
       (throw (ex-info (str "command already has an owner: " command) {:type :owner-exists :command command})))
     (fn unregister []
       (when (identical? owner (get-in @(:state bus) [:owners command]))
         (swap! (:state bus) update :owners dissoc command)
         (async/close! ch)
         true)))))

(defn owners
  "The command keywords that currently have an owner, sorted."
  [bus]
  (sort (keys (:owners @(:state bus)))))

(defn request!
  "Send `request` (a map with a :command keyword) to its owner and return
   the reply map. Without an owner: {:ok false :error {:type :unknown-command}}.
   The whole exchange (waiting for queue room, then for the reply) shares
   one budget, :timeout-ms (default :request-timeout-ms); on expiry the
   reply is {:ok false :error {:type :timeout :phase :enqueue|:reply}}."
  ([bus request] (request! bus request {}))
  ([bus {:keys [command] :as request} {:keys [timeout-ms]}]
   (bump! bus :requests)
   (if-let [{:keys [ch]} (get-in @(:state bus) [:owners command])]
     (let [reply (async/promise-chan)
           t (async/timeout (or timeout-ms (get-in bus [:opts :request-timeout-ms])))
           [v port] (async/alts!! [[ch {:request request :reply reply}] t])]
       (cond
         (= port t) (do (bump! bus :timeouts) {:ok false :error {:type :timeout :command command :phase :enqueue}})
         (false? v) {:ok false :error {:type :unknown-command :command command}}
         :else (let [[r port] (async/alts!! [reply t])]
                 (if (= port reply)
                   r
                   (do (bump! bus :timeouts) {:ok false :error {:type :timeout :command command :phase :reply}})))))
     {:ok false :error {:type :unknown-command :command command}})))

;; ---------------------------------------------------------------------------
;; Introspection and lifecycle

(defn stats
  "Counters plus the current subscriber and owner counts."
  [bus]
  (let [s @(:state bus)]
    (assoc (:stats s)
           :subscribers (count (:subscribers s))
           :owners (count (:owners s))
           :open? (boolean (:open? s)))))

(defn- await-thread [ch timeout-ms]
  (let [[_ port] (async/alts!! [ch (async/timeout timeout-ms)])]
    (= port ch)))

(defn stop!
  "Stop accepting events, let the dispatcher drain its inbox, close every
   subscriber and owner channel and wait (bounded) for their threads."
  [bus]
  (let [{:keys [in state dispatcher]} bus
        s (swap! state assoc :open? false)]
    (async/close! in)
    (await-thread dispatcher 5000)
    (doseq [{:keys [ch thread]} (concat (vals (:subscribers s)) (vals (:owners s)))]
      (async/close! ch)
      (await-thread thread 5000))
    (swap! state assoc :subscribers {} :by-type {} :owners {})
    nil))
