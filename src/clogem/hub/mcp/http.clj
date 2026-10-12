;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.http
  "Streamable HTTP binding of the MCP facade (docs/mcp-compliance.md).

   One endpoint, POST /mcp, bound to 127.0.0.1 only. In order: Host and
   Origin are checked (DNS-rebinding defence), the bearer token is checked
   against clients.edn (401 otherwise; the client's profile scopes everything
   that follows), the body must be application/json, and modern (2026-07-28)
   requests must carry MCP-Protocol-Version, Mcp-Method and Mcp-Name headers
   consistent with the body. Notifications are answered with 202. Requests
   are answered with one JSON object, except subscriptions/listen whose
   response is a long-lived SSE stream (clogem.hub.mcp.subscriptions). The
   legacy GET stream is not served (405). tools/call, resources/read,
   subscriptions/listen and rejected authentications are recorded in the
   audit table."
  (:require [clojure.string :as str]
            [org.httpkit.server :as hk]
            [clogem.hub.audit :as audit]
            [clogem.hub.auth :as auth]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.jsonrpc :as rpc]
            [clogem.hub.mcp.methods :as methods]
            [clogem.hub.mcp.subscriptions :as subs]))

(def endpoint "/mcp")

(def json-headers {"Content-Type" "application/json"})

(defn- json-response [status body]
  {:status status :headers json-headers :body (rpc/encode body)})

(defn- forbidden [reason]
  (json-response 403 (rpc/error-response nil rpc/invalid-request (str "Forbidden: " reason))))

(def www-authenticate "Bearer realm=\"clogem-hub\"")

(defn- unauthorized []
  (-> (json-response 401 (rpc/error-response nil rpc/invalid-request
                                             "Unauthorized: a bearer token from clients.edn is required"))
      (assoc-in [:headers "WWW-Authenticate"] www-authenticate)))

;; ---------------------------------------------------------------------------
;; Header value encoding (Base64 sentinel)

(def ^:private sentinel-re #"^=\?base64\?([A-Za-z0-9+/=]*)\?=$")

(defn decode-header-value
  "Decode the `=?base64?…?=` sentinel the spec uses for non-ASCII header
   values; plain values pass through."
  [v]
  (if-let [[_ b64] (and v (re-matches sentinel-re v))]
    (try (String. (.decode (java.util.Base64/getDecoder) ^String b64) "UTF-8")
         (catch Exception _ v))
    v))

(defn encode-header-value
  "Inverse of decode-header-value: plain printable ASCII without leading or
   trailing whitespace stays as is, anything else is wrapped in the sentinel."
  [^String v]
  (if (and (re-matches #"[\x21-\x7E](?:[\x20-\x7E]*[\x21-\x7E])?" v)
           (not (re-matches sentinel-re v)))
    v
    (str "=?base64?" (.encodeToString (java.util.Base64/getEncoder) (.getBytes v "UTF-8")) "?=")))

;; ---------------------------------------------------------------------------
;; Host and Origin

(defn host-ok?
  "The Host header must name this loopback listener."
  [host port]
  (let [hosts #{"127.0.0.1" "localhost" "[::1]"}]
    (boolean (and host
                  (or (contains? (set (map #(str % ":" port) hosts)) host)
                      (and (= 80 port) (contains? hosts host)))))))

(def ^:private loopback-origin-re #"^https?://(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$")

(defn origin-ok?
  "Absent Origin is fine (native clients); loopback and configured origins
   are accepted; everything else, `null` included, is rejected."
  [origin allowed]
  (boolean (or (nil? origin)
               (re-matches loopback-origin-re origin)
               (contains? (set allowed) origin))))

;; ---------------------------------------------------------------------------
;; Header validation against the body

(def ^:private named-methods #{"tools/call" "resources/read" "prompts/get"})

(defn- body-name [message]
  (or (get-in message [:params :name]) (get-in message [:params :uri])))

(defn header-mismatch
  "For a modern request: a reason string when the mirrored headers are
   missing or disagree with the body, else nil."
  [headers message]
  (let [version (get headers "mcp-protocol-version")
        method (get headers "mcp-method")
        mcp-name (some-> (get headers "mcp-name") decode-header-value)
        body-version (methods/requested-version message)
        body-method (:method message)
        expected-name (body-name message)]
    (cond
      (nil? version) "MCP-Protocol-Version header is required"
      (not= version body-version)
      (str "MCP-Protocol-Version header value '" version "' does not match body value '" body-version "'")
      (nil? method) "Mcp-Method header is required"
      (not= method body-method)
      (str "Mcp-Method header value '" method "' does not match body value '" body-method "'")
      (and (contains? named-methods body-method) (nil? mcp-name))
      (str "Mcp-Name header is required for " body-method)
      (and (contains? named-methods body-method) (not= mcp-name (str expected-name)))
      (str "Mcp-Name header value '" mcp-name "' does not match body value '" expected-name "'")
      :else nil)))

(defn- legacy-version-problem
  "A legacy request may carry MCP-Protocol-Version; if it does, the value
   must be a version the hub supports."
  [headers]
  (when-let [v (get headers "mcp-protocol-version")]
    (when-not (contains? (set methods/supported-versions) v) v)))

;; ---------------------------------------------------------------------------
;; Request handling

(defn- message-id [m]
  (when (and (map? m) (rpc/valid-id? (:id m))) (:id m)))

(defn- read-body [req]
  (let [body (:body req)]
    (cond (nil? body) ""
          (string? body) body
          :else (slurp body))))

(defn- error-status
  "HTTP status for a protocol error response: the status the method layer
   demanded, else 400 for framing errors, else 200."
  [{:keys [http-status response]}]
  (or http-status
      (let [code (get-in response [:error :code])]
        (if (contains? #{rpc/parse-error rpc/invalid-request} code) 400 200))))

(def ^:private audited-methods #{"tools/call" "resources/read" "subscriptions/listen"})

(defn- audit-request!
  "One audit row for a request the policy records."
  [{:keys [audit client profile]} message started-ns {:keys [response]}]
  (when (and audit (contains? audited-methods (:method message)))
    (let [error (:error response)
          result (:result response)]
      (audit/record! audit {:client client :profile profile
                            :method (:method message) :name (body-name message)
                            :ok? (and (nil? error) (not (:isError result)))
                            :error (cond error (str (:code error) " " (:message error))
                                         (:isError result) "isError")
                            :duration-ms (quot (- (System/nanoTime) started-ns) 1000000)
                            :request-id (message-id message)}))))

(declare stream-response)

(defn- dispatch-and-respond [deps req message]
  (let [started (System/nanoTime)
        {:keys [kind response] :as outcome} (rpc/handle message (methods/dispatcher deps))]
    (audit-request! deps message started outcome)
    (case kind
      (:notification :response) {:status 202 :headers {} :body ""}
      :invalid (json-response 400 response)
      :request (cond
                 (:error response) (json-response (error-status outcome) response)
                 (methods/listen-result? (:result response)) (stream-response deps req message (:listen/filter (:result response)))
                 :else (json-response 200 response)))))

(defn- stream-response
  "Answer a subscriptions/listen request with an SSE stream fed by the
   subscription feed: headers and the acknowledgment first, notifications as
   they come, keep-alive comments in between; closing by the client ends the
   subscription; stopping the feed ends it with the listen result."
  [{:keys [subscriptions]} req message filter]
  (if-not subscriptions
    (json-response 200 (rpc/error-response (message-id message) rpc/internal-error
                                           "Internal error: subscriptions are not available"))
    (let [id (message-id message)
          stream (atom nil)
          started? (atom false)]
      (hk/as-channel req
                     {:on-open (fn [ch]
                                 ;; the status line and headers travel with the first frame (the
                                 ;; acknowledgment): an empty first body would end the response
                                 ;; with Content-Length 0 and turn the following chunks into garbage
                                 (reset! stream (subs/open! subscriptions
                                                            {:id id :filter filter :key (System/identityHashCode ch)
                                                             :send! (fn [frame]
                                                                      (if (compare-and-set! started? false true)
                                                                        (hk/send! ch {:status 200 :headers subs/sse-headers :body frame} false)
                                                                        (hk/send! ch frame false)))
                                                             :close! (fn [] (hk/close ch))})))
                      :on-close (fn [_ch status]
                                  (log/debug {:msg "listen stream ended" :subscription id :status status})
                                  (when-let [s @stream] (subs/close! subscriptions s)))}))))

(defn- authenticate
  "deps with :client and :profile for the request's bearer token, or nil."
  [{:keys [clients] :as deps} headers]
  (let [clients (if (instance? clojure.lang.IDeref clients) @clients clients)]
    (when-let [{:keys [client profile]} (auth/authenticate clients (get headers "authorization"))]
      (assoc deps :client client :profile profile))))

(defn handle-post
  "Process one POST /mcp. `deps` holds :registry, :clients (a clients map or
   an atom holding one), :audit (nil-safe), :subscriptions (the feed, nil-safe),
   :port (atom or number) and :allowed-origins."
  [{:keys [port allowed-origins audit] :as deps} req]
  (let [headers (:headers req)
        port (if (instance? clojure.lang.IDeref port) @port port)
        content-type (str (get headers "content-type"))]
    (cond
      (not (host-ok? (get headers "host") port))
      (forbidden "Host header does not name this listener")

      (not (origin-ok? (get headers "origin") allowed-origins))
      (forbidden "Origin not allowed")

      :else
      (if-let [deps (authenticate deps headers)]
        (cond
          (not= "application/json" (-> content-type (str/split #";") first str/trim str/lower-case))
          (json-response 415 (rpc/error-response nil rpc/invalid-request
                                                 "Unsupported Media Type: Content-Type must be application/json"))

          :else
          (let [{:keys [message error]} (rpc/parse (read-body req))]
            (cond
              error (json-response 400 error)

              (and (map? message) (string? (:method message)) (= :modern (methods/era message)))
              (if-let [reason (header-mismatch headers message)]
                (json-response 400 (rpc/error-response (message-id message) methods/header-mismatch
                                                       (str "Header mismatch: " reason)))
                (dispatch-and-respond deps req message))

              (and (map? message) (string? (:method message)) (legacy-version-problem headers))
              (let [requested (legacy-version-problem headers)]
                (json-response 400 (rpc/error-response (message-id message) methods/unsupported-protocol-version
                                                       (str "Unsupported protocol version: " requested)
                                                       {:supported methods/supported-versions :requested requested})))

              :else (dispatch-and-respond deps req message))))
        (do (audit/record! audit {:client "-" :profile :none :method (or (get headers "mcp-method") "?")
                                  :name (some-> (get headers "mcp-name") decode-header-value)
                                  :ok? false :error "unauthorized" :duration-ms 0})
            (unauthorized))))))

(defn handler
  "Ring-style handler for http-kit."
  [deps]
  (fn [req]
    (let [res (cond
                (not= endpoint (:uri req))
                (json-response 404 (rpc/error-response nil rpc/invalid-request "Not found"))

                (not= :post (:request-method req))
                {:status 405 :headers (assoc json-headers "Allow" "POST")
                 :body (rpc/encode (rpc/error-response nil rpc/invalid-request "Method Not Allowed: use POST"))}

                :else (handle-post deps req))]
      (log/debug {:msg "http" :method (:request-method req) :uri (:uri req) :status (:status res)})
      res)))
