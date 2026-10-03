;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.http
  "Streamable HTTP binding of the MCP facade (docs/mcp-compliance.md).

   One endpoint, POST /mcp, bound to 127.0.0.1 only. Host and Origin are
   checked (DNS-rebinding defence), the body must be application/json, and
   modern (2026-07-28) requests must carry MCP-Protocol-Version, Mcp-Method
   and Mcp-Name headers consistent with the body. Notifications are answered
   with 202. Responses are JSON only in S01; the subscription stream (SSE)
   arrives in S03. No authentication until S03: loopback binding plus these
   checks bound the exposure (ADR-0002)."
  (:require [clojure.string :as str]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.jsonrpc :as rpc]
            [clogem.hub.mcp.methods :as methods]))

(def endpoint "/mcp")

(def json-headers {"Content-Type" "application/json"})

(defn- json-response [status body]
  {:status status :headers json-headers :body (rpc/encode body)})

(defn- forbidden [reason]
  (json-response 403 (rpc/error-response nil rpc/invalid-request (str "Forbidden: " reason))))

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

(declare dispatch-and-respond)

(defn handle-post
  "Process one POST /mcp. `deps` holds :registry, :profile, :port (atom or
   number) and :allowed-origins."
  [{:keys [port allowed-origins] :as deps} req]
  (let [headers (:headers req)
        port (if (instance? clojure.lang.IDeref port) @port port)
        content-type (str (get headers "content-type"))]
    (cond
      (not (host-ok? (get headers "host") port))
      (forbidden "Host header does not name this listener")

      (not (origin-ok? (get headers "origin") allowed-origins))
      (forbidden "Origin not allowed")

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
            (dispatch-and-respond deps message))

          (and (map? message) (string? (:method message)) (legacy-version-problem headers))
          (let [requested (legacy-version-problem headers)]
            (json-response 400 (rpc/error-response (message-id message) methods/unsupported-protocol-version
                                                   (str "Unsupported protocol version: " requested)
                                                   {:supported methods/supported-versions :requested requested})))

          :else (dispatch-and-respond deps message))))))

(defn- dispatch-and-respond [deps message]
  (let [{:keys [kind response] :as outcome} (rpc/handle message (methods/dispatcher deps))]
    (case kind
      (:notification :response) {:status 202 :headers {} :body ""}
      :invalid (json-response 400 response)
      :request (json-response (if (:error response) (error-status outcome) 200) response))))

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
