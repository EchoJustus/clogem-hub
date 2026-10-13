;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.llm
  "The LLM adapter behind clogem.api/llm-chat! (S04, ADR-0007).

   One provider in S04: Ollama, spoken to over HTTP at the configured base
   URL only (PD-9: `clogem.hub.config/validate` refuses a non-local URL
   unless `:allow-remote? true`; this namespace takes the URL it is given).
   A chat is one synchronous POST to `/api/chat` with `stream false`, the
   request's `format` passed through (nil, \"json\" or a JSON Schema map),
   `options.temperature` (default 0) merged over the request's provider
   options, `keep_alive` from the configuration and base64 `images` per
   message. The reply is the clogem.api contract:
   {:content string :json value-or-nil :model string :provider :ollama
    :done-reason string-or-nil :duration-ms int :raw map}
   where :json is the parsed content when :format was given and the content
   is valid JSON (nil otherwise, with a warning that names no content) and
   :raw is the whole response object with keyword keys (durations in it are
   Ollama's nanoseconds; :duration-ms is the wall clock of the call).

   Errors are ex-info maps with :type :llm/invalid-request (a request that
   breaks the contract: missing messages, an unknown role, no model),
   :llm/unreachable (the connection failed: refused, no route, a connect
   timeout, a reset), :llm/timeout (the request timeout elapsed before the
   response arrived) and :llm/provider-error (a non-2xx status, or a 2xx
   body that is not a response object; carries :status and the body's
   `error`). Message contents, images and model output are never logged
   (PD: never full transcripts); log lines carry the model, the status, the
   duration and counts only.

   `preflight` asks `GET /api/version` and `GET /api/tags` and answers
   {:ok true :version :models [names]} (plus :model-available? when a model
   is configured) or {:ok false :error {…}}; it never throws for a
   provider that is down.

   Sources (PD-11, read 2026-10-12 and 2026-10-13):
   https://raw.githubusercontent.com/ollama/ollama/main/docs/api.md and
   https://docs.ollama.com/api/chat.md (request and response fields,
   stream false, format as json or a JSON schema, options.temperature,
   keep_alive, images as base64 strings, done_reason),
   https://docs.ollama.com/capabilities/structured-outputs.md (temperature
   0, parse message.content), https://docs.ollama.com/api/errors.md (400,
   404, 429, 500, 502 with {\"error\": \"…\"}), https://docs.ollama.com/api/tags.md
   and https://docs.ollama.com/api-reference/get-version.md,
   https://docs.ollama.com/faq.md (keep_alive values, 127.0.0.1:11434);
   https://github.com/babashka/http-client README (`:timeout` per request,
   `:connect-timeout` on `http/client`, `:throw false` to receive non-2xx
   responses as maps). The exception classes were established by running
   bb 1.13.225 against a closed port (java.net.ConnectException), a slow
   handler (java.net.http.HttpTimeoutException, \"request timed out\") and
   a black-hole address (java.net.http.HttpConnectTimeoutException, a
   subclass of HttpTimeoutException that bb cannot name in a catch clause,
   hence the classification by class name)."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]))

(def roles
  "Message roles the clogem.api contract allows."
  #{"system" "user" "assistant"})

(def connect-timeout-ms
  "How long a connection attempt may take before the endpoint counts as
   unreachable (a LAN host that is off answers nothing; without this a
   call would wait the whole request timeout)."
  10000)

(def ^:private error-body-max-chars
  "Cap on the body text copied into a :llm/provider-error when the body is
   not the documented {\"error\": …} object."
  500)

(defprotocol Provider
  "A chat-completion provider. `-chat` receives a request `chat` validated
   and defaulted (:messages :model :format :temperature :timeout-ms
   :options, plus ::module for the log line) and returns the reply map or
   throws one of the contract's ex-info errors. `-preflight` reports the
   provider's reachability and models without throwing."
  (-chat [p request])
  (-preflight [p]))

;; ---------------------------------------------------------------------------
;; Request validation (provider-independent)

(defn- invalid! [reason data]
  (throw (ex-info (str "llm-chat!: " reason) (merge {:type :llm/invalid-request} data))))

(defn- check-message! [i m]
  (when-not (map? m)
    (invalid! (str "message " i " must be a map") {:index i}))
  (let [{:keys [role content images]} m]
    (when-not (contains? roles role)
      (invalid! (str "message " i " has role " (pr-str role) "; expected one of " (str/join ", " (sort roles)))
                {:index i :role role}))
    (when-not (string? content)
      (invalid! (str "message " i " must have a string :content") {:index i}))
    (when-not (or (nil? images) (and (sequential? images) (every? string? images)))
      (invalid! (str "message " i " :images must be a sequence of base64 strings") {:index i}))))

(defn validate-request
  "`request` checked against the clogem.api contract and completed from
   `defaults` ({:model :timeout-ms}): :model falls back to the default,
   :temperature to the one in :options, then 0, :timeout-ms to the default.
   Throws ex-info :llm/invalid-request."
  [request {:keys [model timeout-ms] :as _defaults}]
  (when-not (map? request)
    (invalid! "request must be a map" {}))
  (let [{:keys [messages format options]} request
        model* (or (:model request) model)
        temperature (or (:temperature request) (:temperature options) 0)
        timeout* (or (:timeout-ms request) timeout-ms)]
    (when-not (and (sequential? messages) (seq messages))
      (invalid! ":messages must be a non-empty sequence of {:role :content}" {}))
    (doall (map-indexed check-message! messages))
    (when-not (and (string? model*) (not (str/blank? model*)))
      (invalid! "no :model in the request and none configured" {:model model*}))
    (when-not (or (nil? format) (= "json" format) (map? format))
      (invalid! ":format must be nil, \"json\" or a JSON Schema map" {:format (type format)}))
    (when-not (number? temperature)
      (invalid! ":temperature must be a number" {:temperature temperature}))
    (when-not (and (integer? timeout*) (pos? timeout*))
      (invalid! ":timeout-ms must be a positive integer" {:timeout-ms timeout*}))
    (when-not (or (nil? options) (map? options))
      (invalid! ":options must be a map" {:options (type options)}))
    (assoc request
           :messages (vec messages)
           :model model*
           :temperature temperature
           :timeout-ms timeout*
           :options (or options {}))))

;; ---------------------------------------------------------------------------
;; HTTP errors

(defn class-names
  "The names of `c` and its superclasses, most specific first."
  [^Class c]
  (mapv (fn [^Class k] (.getName k)) (take-while some? (iterate (fn [^Class k] (.getSuperclass k)) c))))

(defn error-type-of
  "The contract's error type for an exception whose class hierarchy is
   `names` (see `class-names`): a connect timeout and every other
   java.io.IOException mean :llm/unreachable, a request timeout
   :llm/timeout; nil for anything else (which is a bug, not a provider
   condition, and propagates)."
  [names]
  (let [names (set names)]
    (cond
      (contains? names "java.net.http.HttpConnectTimeoutException") :llm/unreachable
      (contains? names "java.net.http.HttpTimeoutException") :llm/timeout
      (contains? names "java.io.IOException") :llm/unreachable
      :else nil)))

(defn exception-type
  "`error-type-of` for a thrown exception."
  [^Throwable e]
  (error-type-of (class-names (class e))))

(defn- http-error
  "The contract's ex-info for an exception the HTTP client raised, or the
   exception itself when it is not a transport condition."
  [^Throwable e {:keys [url model timeout-ms]}]
  (if-let [type (exception-type e)]
    (ex-info (str "llm-chat!: " (case type
                                  :llm/timeout (str "no response from " url " within " timeout-ms " ms")
                                  (str url " is unreachable: " (or (ex-message e) (.getName (class e))))))
             {:type type :provider :ollama :url url :model model :timeout-ms timeout-ms
              :cause (.getName (class e))})
    e))

(defn- parse-json-body
  "The body parsed with keyword keys, or ::invalid."
  [body]
  (try (json/parse-string (str body) true)
       (catch Exception _ ::invalid)))

(defn- body-error
  "The error text of a non-2xx (or malformed) response body: its `error`
   property when the body is the documented object, else the body text,
   capped."
  [body parsed]
  (let [text (str/trim (str body))]
    (cond
      (and (map? parsed) (string? (:error parsed))) (:error parsed)
      (str/blank? text) nil
      :else (subs text 0 (min (count text) error-body-max-chars)))))

(defn- provider-error [reason {:keys [url model status error]}]
  (ex-info (str "llm-chat!: " reason)
           {:type :llm/provider-error :provider :ollama :url url :model model :status status :error error}))

;; ---------------------------------------------------------------------------
;; Ollama

(defn endpoint
  "`path` under the provider's base URL, tolerating a trailing slash."
  [url path]
  (str (str/replace (str url) #"/+$" "") path))

(defn chat-body
  "The /api/chat request object for a validated request: model, messages
   (with `images` only when given), stream false, format when given,
   options with the temperature, keep_alive when configured."
  [{:keys [keep-alive]} {:keys [model messages format temperature options]}]
  (cond-> {:model model
           :messages (mapv (fn [{:keys [role content images]}]
                             (cond-> {:role role :content content}
                               (seq images) (assoc :images (vec images))))
                           messages)
           :stream false
           :options (assoc options :temperature temperature)}
    (some? format) (assoc :format format)
    (some? keep-alive) (assoc :keep_alive keep-alive)))

(defn- format-kind [format]
  (cond (nil? format) :none (string? format) :json :else :schema))

(defn- parse-content
  "The content parsed as JSON when `format` was given; nil when it is not
   valid JSON (logged as a warning without the content)."
  [content format model]
  (when (some? format)
    (let [parsed (if (str/blank? (str content)) ::invalid (parse-json-body content))]
      (if (= ::invalid parsed)
        (do (log/warn {:event :llm/invalid-json :provider :ollama :model model
                       :format (format-kind format) :content-chars (count (str content))})
            nil)
        parsed))))

(defn- chat-reply
  "The contract reply for a 2xx response body, or a :llm/provider-error
   when the body is not a response object."
  [{:keys [url]} {:keys [model format messages]} body status duration-ms]
  (let [raw (parse-json-body body)]
    (when (or (= ::invalid raw) (not (map? raw)))
      (throw (provider-error (str "response from " url " is not a JSON object")
                             {:url url :model model :status status :error (body-error body nil)})))
    (when (and (:error raw) (nil? (:message raw)))
      (throw (provider-error (str "provider error: " (:error raw))
                             {:url url :model model :status status :error (body-error body raw)})))
    (let [content (str (get-in raw [:message :content] ""))]
      (log/info {:event :llm/chat :provider :ollama :model (or (:model raw) model) :status status
                 :duration-ms duration-ms :format (format-kind format)
                 :messages (count messages) :images (reduce + 0 (map #(count (:images %)) messages))
                 :content-chars (count content) :done-reason (:done_reason raw)})
      {:content content
       :json (parse-content content format model)
       :model (or (:model raw) model)
       :provider :ollama
       :done-reason (:done_reason raw)
       :duration-ms duration-ms
       :raw raw})))

(defn- elapsed-ms [started] (int (- (System/currentTimeMillis) started)))

(defn- fail! [e {:keys [model]} started]
  (log/warn (merge {:event :llm/chat-failed :provider :ollama :model model :duration-ms (elapsed-ms started)}
                   (select-keys (ex-data e) [:type :status :error :url :timeout-ms])))
  (throw e))

(defn- get-json!
  "GET `path` and return the parsed object, or throw the contract's errors."
  [{:keys [url client]} path timeout-ms]
  (let [full (endpoint url path)
        response (try (http/get full {:client client :headers {"Accept" "application/json"}
                                      :timeout timeout-ms :throw false})
                      (catch Exception e (throw (http-error e {:url full :timeout-ms timeout-ms}))))
        {:keys [status body]} response
        parsed (parse-json-body body)]
    (when-not (<= 200 status 299)
      (throw (provider-error (str "GET " path " answered " status)
                             {:url full :status status :error (body-error body parsed)})))
    (when-not (map? parsed)
      (throw (provider-error (str "GET " path " did not answer a JSON object")
                             {:url full :status status :error (body-error body nil)})))
    parsed))

(defn- model-names [tags]
  (vec (keep (fn [m] (when (map? m) (or (:name m) (:model m)))) (:models tags))))

(defrecord Ollama [url model timeout-ms keep-alive client]
  Provider
  (-chat [_ request]
    ;; the request's :model and :timeout-ms are already defaulted from the
    ;; record's by `chat`; the record fields are not read here
    (let [full (endpoint url "/api/chat")
          started (System/currentTimeMillis)
          ctx {:url full :model (:model request) :timeout-ms (:timeout-ms request)}]
      (try
        (let [{:keys [status body]}
              (try (http/post full {:client client
                                    :headers {"Content-Type" "application/json" "Accept" "application/json"}
                                    :body (json/generate-string (chat-body {:keep-alive keep-alive} request))
                                    :timeout (:timeout-ms request)
                                    :throw false})
                   (catch Exception e (throw (http-error e ctx))))
              duration (elapsed-ms started)]
          (if (<= 200 status 299)
            (chat-reply {:url url} request body status duration)
            (let [error (body-error body (parse-json-body body))]
              (throw (provider-error (str url " answered " status (some->> error (str ": ")))
                                     (assoc ctx :status status :error error))))))
        (catch clojure.lang.ExceptionInfo e
          (fail! e request started)))))
  (-preflight [this]
    (let [budget (min timeout-ms 5000)]
      (try
        (let [version (:version (get-json! this "/api/version" budget))
              names (model-names (get-json! this "/api/tags" budget))
              available? (when model
                           (boolean (some #{model (str model ":latest")} names)))]
          (log/info {:event :llm/preflight :provider :ollama :url url :ok true :version version
                     :models (count names) :model model :model-available? available?})
          (cond-> {:ok true :provider :ollama :url url :version version :models names :model model}
            model (assoc :model-available? available?)))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (if (contains? #{:llm/unreachable :llm/timeout :llm/provider-error} (:type data))
              (do (log/warn {:event :llm/preflight :provider :ollama :url url :ok false
                             :type (:type data) :status (:status data) :error (:error data)})
                  {:ok false :provider :ollama :url url :model model :error data})
              (throw e))))))))

(defn ollama
  "An Ollama provider over {:url :model :timeout-ms :keep-alive}; what is
   missing comes from the hub's defaults (`:llm :ollama` in
   clogem.hub.config/defaults). :url is the base URL without /api."
  [opts]
  (let [{:keys [url model timeout-ms keep-alive] :as merged} (merge (get-in config/defaults [:llm :ollama]) opts)]
    (when-not (config/url-host url)
      (throw (ex-info (str "llm: ollama url must be an http(s) URL, got " (pr-str url))
                      {:type :llm/invalid-config :url url})))
    (when-not (and (integer? timeout-ms) (pos? timeout-ms))
      (throw (ex-info (str "llm: ollama timeout-ms must be a positive integer, got " (pr-str timeout-ms))
                      {:type :llm/invalid-config :timeout-ms timeout-ms})))
    (when-not (or (nil? model) (and (string? model) (not (str/blank? model))))
      (throw (ex-info (str "llm: ollama model must be a non-blank string or nil, got " (pr-str model))
                      {:type :llm/invalid-config :model model})))
    (->Ollama (str/replace (str url) #"/+$" "") model timeout-ms keep-alive
              (http/client (assoc http/default-client-opts
                                  :connect-timeout (min timeout-ms (or (:connect-timeout-ms merged) connect-timeout-ms)))))))

;; ---------------------------------------------------------------------------
;; Entry points

(defn chat
  "A chat completion through `provider` for a clogem.api request: validates
   it (:llm/invalid-request), fills :model and :timeout-ms from the
   provider and returns the contract's reply map. `module` (optional) is
   the calling module's id for the log line."
  ([provider request] (chat provider request nil))
  ([provider request module]
   (let [defaults {:model (:model provider) :timeout-ms (:timeout-ms provider)}
         validated (validate-request request defaults)]
     (-chat provider (cond-> validated module (assoc ::module module))))))

(defn preflight
  "{:ok true :version … :models [names]} or {:ok false :error {…}}: the
   provider's version and locally available models. Never throws for a
   provider that is down or answers an error."
  [provider]
  (-preflight provider))

(defn chat-fn
  "The runtime's `:llm` function, (fn [ctx request] reply), for a hub
   configuration: an Ollama provider from `(:llm config)` whose model is
   the default for requests that name none."
  [config]
  (let [{:keys [provider] :as llm-config} (:llm config)
        provider (if (or (nil? provider) (= :ollama provider))
                   (ollama (:ollama llm-config))
                   (throw (ex-info (str "llm provider must be :ollama, got " (pr-str provider))
                                   {:type :llm/invalid-config :provider provider})))]
    (fn [ctx request]
      (chat provider request (:module/id ctx)))))
