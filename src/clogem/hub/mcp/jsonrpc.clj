;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.jsonrpc
  "JSON-RPC 2.0 framing for the MCP facade: pure functions, no IO.

   Rules: `jsonrpc` must be \"2.0\"; request ids are strings or integers;
   batch arrays are rejected with -32600 (MCP dropped batching); a
   notification (no id) gets no response; protocol errors use the standard
   codes. A tool's own failure is never a protocol error: the methods layer
   turns it into a result with isError true."
  (:require [cheshire.core :as json]))

(def parse-error -32700)
(def invalid-request -32600)
(def method-not-found -32601)
(def invalid-params -32602)
(def internal-error -32603)

(defn error-response
  "A JSON-RPC error object for `id` (nil when the id is unknown)."
  ([id code message] (error-response id code message nil))
  ([id code message data]
   {:jsonrpc "2.0" :id id
    :error (cond-> {:code code :message message} (some? data) (assoc :data data))}))

(defn result-response [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn rpc-error
  "An exception the dispatch function throws to answer with a protocol error.
   `http-status` is the status an HTTP transport must use for this error
   (the MCP spec mandates 400 or 404 for some codes); nil means the
   transport's default."
  ([code message] (rpc-error code message nil))
  ([code message data] (rpc-error code message data nil))
  ([code message data http-status]
   (ex-info message {::code code ::data data ::http-status http-status})))

(defn valid-id? [id] (or (string? id) (integer? id)))

(defn parse
  "Parse a JSON body. Returns {:message m} or {:error response}."
  [^String body]
  (try
    (let [values (doall (json/parsed-seq (java.io.StringReader. (str body)) true))]
      (if (= 1 (count values))
        {:message (first values)}
        {:error (error-response nil parse-error
                                (if (empty? values) "Parse error: empty body" "Parse error: exactly one JSON-RPC message per body"))}))
    (catch Exception _
      {:error (error-response nil parse-error "Parse error")})))

(defn classify
  "{:kind :request|:notification|:response|:invalid, :error response-when-invalid}."
  [m]
  (cond
    (sequential? m)
    {:kind :invalid :error (error-response nil invalid-request "Batch requests are not supported")}

    (not (map? m))
    {:kind :invalid :error (error-response nil invalid-request "Invalid Request")}

    (not= "2.0" (:jsonrpc m))
    {:kind :invalid :error (error-response (when (valid-id? (:id m)) (:id m)) invalid-request
                                           "Invalid Request: jsonrpc must be \"2.0\"")}

    (string? (:method m))
    (cond
      (and (contains? m :params) (not (or (map? (:params m)) (sequential? (:params m)))))
      {:kind :invalid :error (error-response (when (valid-id? (:id m)) (:id m)) invalid-request
                                             "Invalid Request: params must be an object or array")}
      (not (contains? m :id)) {:kind :notification}
      (valid-id? (:id m)) {:kind :request}
      :else {:kind :invalid :error (error-response nil invalid-request
                                                   "Invalid Request: id must be a string or integer")})

    (or (contains? m :result) (contains? m :error))
    {:kind :response}

    :else
    {:kind :invalid :error (error-response (when (valid-id? (:id m)) (:id m)) invalid-request
                                           "Invalid Request: missing method")}))

(defn handle
  "Dispatch one parsed message. `dispatch` is (fn [method params message] result)
   and may throw `rpc-error` for protocol errors. Returns
   {:kind k :response r} where :response is nil for notifications and
   client responses."
  [m dispatch]
  (let [{:keys [kind error]} (classify m)]
    (case kind
      :invalid {:kind :invalid :response error}
      :response {:kind :response :response nil}
      :notification (do (try (dispatch (:method m) (:params m) m)
                             (catch Exception _ nil))
                        {:kind :notification :response nil})
      :request (let [id (:id m)]
                 (try
                   {:kind :request
                    :response (result-response id (dispatch (:method m) (:params m) m))}
                   (catch clojure.lang.ExceptionInfo e
                     (let [{::keys [code data http-status]} (ex-data e)]
                       (if code
                         (cond-> {:kind :request :response (error-response id code (ex-message e) data)}
                           http-status (assoc :http-status http-status))
                         {:kind :request :response (error-response id internal-error "Internal error")})))
                   (catch Exception _
                     {:kind :request :response (error-response id internal-error "Internal error")}))))))

(defn handle-body
  "Parse and dispatch a request body. Returns {:kind k :response r}."
  [body dispatch]
  (let [{:keys [message error]} (parse body)]
    (if error
      {:kind :invalid :response error}
      (handle message dispatch))))

(defn encode
  "Compact JSON for a response map."
  [response]
  (json/generate-string response))
