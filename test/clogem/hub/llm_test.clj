;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.llm-test
  "The Ollama adapter against a fake Ollama: an http-kit server on
   127.0.0.1 (port 0) that records every request and answers golden
   responses copied from the Ollama API documentation (read 2026-10-12).
   No real model, no network beyond loopback."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as hk]
            [clogem.hub.config :as config]
            [clogem.hub.llm :as llm]
            [clogem.hub.log :as log]))

;; ---------------------------------------------------------------------------
;; The fake Ollama

(def ^:private golden-plain
  "api.md, 'Chat request (No streaming)', with done_reason from the OpenAPI example."
  {:model "plain"
   :created_at "2023-12-12T14:13:43.416799Z"
   :message {:role "assistant" :content "Hello! How are you today?"}
   :done true
   :done_reason "stop"
   :total_duration 5191566416
   :load_duration 2154458
   :prompt_eval_count 26
   :prompt_eval_duration 383809000
   :eval_count 298
   :eval_duration 4799921000})

(def ^:private golden-structured
  "api.md, 'Chat request (Structured outputs)'."
  {:model "structured"
   :created_at "2024-12-06T00:46:58.265747Z"
   :message {:role "assistant" :content "{\"age\": 22, \"available\": false}"}
   :done_reason "stop"
   :done true
   :total_duration 2254970291
   :load_duration 574751416
   :prompt_eval_count 34
   :prompt_eval_duration 1502000000
   :eval_count 12
   :eval_duration 175000000})

(def ^:private golden-tags
  "api.md, 'List Local Models' (one entry replaced by a vision model)."
  {:models [{:name "llama3.2:latest" :model "llama3.2:latest"
             :modified_at "2025-05-04T17:37:44.706015396-07:00" :size 2019393189
             :digest "a80c4f17acd55265feec403c7aef86be0c25983ab279d83f3bcd3abbcb5b8b72"
             :details {:parent_model "" :format "gguf" :family "llama" :families ["llama"]
                       :parameter_size "3.2B" :quantization_level "Q4_K_M"}}
            {:name "llava:latest" :model "llava:latest"
             :modified_at "2025-05-10T08:06:48.639712648-07:00" :size 4683075271
             :digest "0a8c266910232fd3291e71e5ba1e058cc5af9d411192cf88b6d30e92b6e73163"
             :details {:parent_model "" :format "gguf" :family "llama" :families ["llama" "clip"]
                       :parameter_size "7B" :quantization_level "Q4_0"}}]})

(defn- json-response [status body]
  {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)})

(defn- chat-response
  "What the fake answers to POST /api/chat, chosen by the requested model."
  [{:keys [model]}]
  (case model
    "plain" (json-response 200 golden-plain)
    "structured" (json-response 200 golden-structured)
    "broken" (json-response 200 (assoc-in golden-structured [:message :content] "{\"age\": 22, "))
    "slow" (do ;; the client gives up long before; a server stop may interrupt the sleep
             (try (Thread/sleep 1500) (catch InterruptedException _ nil))
             (json-response 200 golden-plain))
    "garbage" {:status 200 :headers {"Content-Type" "text/html"} :body "<html>not ollama</html>"}
    "errorish" (json-response 200 {:error "the model failed to generate a response"})
    "overloaded" {:status 500 :headers {"Content-Type" "text/plain"} :body "upstream exploded"}
    (json-response 404 {:error (str "model '" model "' not found")})))

(defn- fake-ollama
  "A ring handler recording every request (parsed body included) in `received`."
  [received]
  (fn [{:keys [request-method uri body headers]}]
    (let [raw (some-> body slurp)
          parsed (when (seq raw) (json/parse-string raw true))]
      (swap! received conj {:method request-method :uri uri :headers headers :body parsed :raw raw})
      (case [request-method uri]
        [:get "/api/version"] (json-response 200 {:version "0.12.6"})
        [:get "/api/tags"] (json-response 200 golden-tags)
        [:post "/api/chat"] (chat-response parsed)
        (json-response 404 {:error "not found"})))))

(def ^:private state (atom nil))

(defn- url [] (str "http://127.0.0.1:" (:port @state)))
(defn- received [] @(:received @state))
(defn- last-request [] (last (received)))

(defn- await-record!
  "Wait (up to 5 s) until the fake has recorded a request for `model`: the
   slow handler records on the server's worker thread after the client
   gave up, and must not leak into the next test's `received`."
  [model]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (while (and (not-any? #(= model (get-in % [:body :model])) (received))
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 10))))

(use-fixtures :once
  (fn [f]
    (log/set-level! :error)
    (let [received (atom [])
          server (hk/run-server (fake-ollama received) {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
      (reset! state {:server server :port (hk/server-port server) :received received})
      (try (f)
           (finally
             (hk/server-stop! server)
             (reset! state nil)
             (log/set-level! :info))))))

(use-fixtures :each (fn [f] (reset! (:received @state) []) (f)))

(defn- provider
  ([] (provider {}))
  ([opts] (llm/ollama (merge {:url (url) :timeout-ms 5000 :keep-alive "5m"} opts))))

(defn- closed-port
  "A loopback port nothing listens on: bound and released just now."
  []
  (let [ss (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort ss)]
    (.close ss)
    port))

(defn- thrown-data
  "The ex-data of the ExceptionInfo `thunk` throws, or ::no-throw."
  [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(def ^:private messages [{:role "system" :content "You are terse."}
                         {:role "user" :content "why is the sky blue?"}])

;; ---------------------------------------------------------------------------
;; Chat

(deftest plain-chat-reply-and-request-body
  (let [reply (llm/chat (provider) {:messages messages :model "plain"})
        {:keys [method uri headers body]} (last-request)]
    (testing "the reply is the clogem.api contract"
      (is (= "Hello! How are you today?" (:content reply)))
      (is (nil? (:json reply)) "no :format, so nothing is parsed")
      (is (= "plain" (:model reply)))
      (is (= :ollama (:provider reply)))
      (is (= "stop" (:done-reason reply)))
      (is (integer? (:duration-ms reply)))
      (is (<= 0 (:duration-ms reply)))
      (is (= golden-plain (:raw reply)) "the whole response object, keyword keys")
      (is (= #{:content :json :model :provider :done-reason :duration-ms :raw} (set (keys reply)))))
    (testing "the request Ollama received"
      (is (= :post method))
      (is (= "/api/chat" uri))
      (is (= "application/json" (get headers "content-type")))
      (is (= "plain" (:model body)))
      (is (false? (:stream body)) "stream false: one response object")
      (is (= 0 (get-in body [:options :temperature])) "temperature defaults to 0")
      (is (= "5m" (:keep_alive body)))
      (is (not (contains? body :format)) "no format key when none was given")
      (is (= [{:role "system" :content "You are terse."} {:role "user" :content "why is the sky blue?"}]
             (:messages body)))
      (is (not-any? #(contains? % :images) (:messages body)) "no images key without images"))))

(deftest structured-output-and-images
  (let [schema {"type" "object"
                "properties" {"age" {"type" "integer"} "available" {"type" "boolean"}}
                "required" ["age" "available"]}
        reply (llm/chat (provider) {:messages [{:role "user"
                                                :content "Return a JSON object with the age and availability."
                                                :images ["aGVsbG8=" "d29ybGQ="]}]
                                    :model "structured"
                                    :format schema
                                    :temperature 0.2
                                    :options {:num_ctx 8192 :seed 7}})
        body (:body (last-request))]
    (is (= "{\"age\": 22, \"available\": false}" (:content reply)) "the content stays the raw string")
    (is (= {:age 22 :available false} (:json reply)) "and :json is its parse")
    (is (= {:type "object"
            :properties {:age {:type "integer"} :available {:type "boolean"}}
            :required ["age" "available"]}
           (:format body))
        "the JSON Schema is passed through as an object")
    (is (= ["aGVsbG8=" "d29ybGQ="] (get-in body [:messages 0 :images])) "images ride on the message")
    (is (= {:num_ctx 8192 :seed 7 :temperature 0.2} (:options body))
        "provider options are merged under the explicit temperature")))

(deftest json-format-string-passes-through
  (llm/chat (provider) {:messages messages :model "structured" :format "json"})
  (is (= "json" (:format (:body (last-request))))))

(deftest invalid-json-content-with-format-gives-json-nil
  (let [reply (llm/chat (provider) {:messages messages :model "broken" :format {"type" "object"}})]
    (is (= "{\"age\": 22, " (:content reply)))
    (is (nil? (:json reply)))
    (is (= "stop" (:done-reason reply)))))

(deftest temperature-in-options-is-honoured-when-no-explicit-one
  (llm/chat (provider) {:messages messages :model "plain" :options {:temperature 0.9}})
  (is (= 0.9 (get-in (:body (last-request)) [:options :temperature]))))

(deftest keep-alive-is-omitted-when-not-configured
  (llm/chat (provider {:keep-alive nil}) {:messages messages :model "plain"})
  (is (not (contains? (:body (last-request)) :keep_alive))))

(deftest trailing-slash-on-the-url-is-tolerated
  (let [p (llm/ollama {:url (str (url) "/") :timeout-ms 5000})]
    (is (= "Hello! How are you today?" (:content (llm/chat p {:messages messages :model "plain"}))))
    (is (= "/api/chat" (:uri (last-request))))))

;; ---------------------------------------------------------------------------
;; Errors

(deftest unknown-model-is-a-provider-error
  (let [data (thrown-data #(llm/chat (provider) {:messages messages :model "missing"}))]
    (is (= :llm/provider-error (:type data)))
    (is (= 404 (:status data)))
    (is (= "model 'missing' not found" (:error data)) "the body's error property")
    (is (= :ollama (:provider data)))
    (is (= "missing" (:model data)))))

(deftest non-json-error-body-is-reported-as-text
  (let [data (thrown-data #(llm/chat (provider) {:messages messages :model "overloaded"}))]
    (is (= :llm/provider-error (:type data)))
    (is (= 500 (:status data)))
    (is (= "upstream exploded" (:error data)))))

(deftest a-2xx-that-is-not-a-response-object-is-a-provider-error
  (let [data (thrown-data #(llm/chat (provider) {:messages messages :model "garbage"}))]
    (is (= :llm/provider-error (:type data)))
    (is (= 200 (:status data))))
  (let [data (thrown-data #(llm/chat (provider) {:messages messages :model "errorish"}))]
    (is (= :llm/provider-error (:type data)))
    (is (= "the model failed to generate a response" (:error data)))))

(deftest slow-provider-is-a-timeout
  (let [data (thrown-data #(llm/chat (provider) {:messages messages :model "slow" :timeout-ms 300}))]
    (is (= :llm/timeout (:type data)))
    (is (= 300 (:timeout-ms data)) "the request's timeout overrides the provider's")
    (is (= "java.net.http.HttpTimeoutException" (:cause data))))
  (await-record! "slow"))

(deftest provider-timeout-applies-when-the-request-names-none
  (let [data (thrown-data #(llm/chat (provider {:timeout-ms 300}) {:messages messages :model "slow"}))]
    (is (= :llm/timeout (:type data)))
    (is (= 300 (:timeout-ms data))))
  (await-record! "slow"))

(deftest closed-port-is-unreachable
  (let [port (closed-port)
        p (llm/ollama {:url (str "http://127.0.0.1:" port) :timeout-ms 2000})
        data (thrown-data #(llm/chat p {:messages messages :model "plain"}))]
    (is (= :llm/unreachable (:type data)))
    (is (= (str "http://127.0.0.1:" port "/api/chat") (:url data)))
    (is (= "java.net.ConnectException" (:cause data)))))

(deftest exception-classification
  (testing "by class hierarchy, so a connect timeout (unnameable in bb) is still unreachable"
    (is (= :llm/unreachable (llm/error-type-of ["java.net.http.HttpConnectTimeoutException"
                                                 "java.net.http.HttpTimeoutException"
                                                 "java.io.IOException"])))
    (is (= :llm/timeout (llm/error-type-of ["java.net.http.HttpTimeoutException" "java.io.IOException"])))
    (is (= :llm/unreachable (llm/error-type-of ["java.net.ConnectException" "java.net.SocketException" "java.io.IOException"])))
    (is (nil? (llm/error-type-of ["java.lang.IllegalArgumentException" "java.lang.RuntimeException"]))))
  (testing "on real exception objects"
    (is (= :llm/timeout (llm/exception-type (java.net.http.HttpTimeoutException. "request timed out"))))
    (is (= :llm/unreachable (llm/exception-type (java.net.ConnectException. "refused"))))
    (is (nil? (llm/exception-type (IllegalArgumentException. "bug"))))))

(deftest invalid-requests
  (let [p (provider)
        invalid? (fn [request] (= :llm/invalid-request (:type (thrown-data #(llm/chat p request)))))]
    (is (invalid? {:model "plain"}) "no messages")
    (is (invalid? {:model "plain" :messages []}) "empty messages")
    (is (invalid? {:model "plain" :messages [{:role "tool" :content "x"}]}) "a role outside the contract")
    (is (invalid? {:model "plain" :messages [{:role "user"}]}) "no content")
    (is (invalid? {:model "plain" :messages [{:role "user" :content 42}]}) "content not a string")
    (is (invalid? {:model "plain" :messages [{:role "user" :content "x" :images "aGVsbG8="}]}) "images not a sequence")
    (is (invalid? {:messages messages}) "no model in the request and none configured")
    (is (invalid? {:model "plain" :messages messages :format :json}) "format neither nil, \"json\" nor a map")
    (is (invalid? {:model "plain" :messages messages :temperature "hot"}))
    (is (invalid? {:model "plain" :messages messages :timeout-ms 0}))
    (is (invalid? {:model "plain" :messages messages :options [:num_ctx 1]}))
    (is (invalid? "not a map"))
    (is (empty? (received)) "nothing reached the provider")))

(deftest validate-request-fills-the-defaults
  (let [v (llm/validate-request {:messages (list {:role "user" :content "x"})} {:model "m" :timeout-ms 42})]
    (is (= "m" (:model v)))
    (is (= 42 (:timeout-ms v)))
    (is (= 0 (:temperature v)))
    (is (= {} (:options v)))
    (is (vector? (:messages v))))
  (let [v (llm/validate-request {:messages messages :model "own" :timeout-ms 7 :temperature 1 :options {:seed 1}}
                                {:model "m" :timeout-ms 42})]
    (is (= ["own" 7 1 {:seed 1}] ((juxt :model :timeout-ms :temperature :options) v)))))

(deftest chat-body-is-pure
  (is (= {:model "m"
          :messages [{:role "user" :content "hi" :images ["aa=="]} {:role "assistant" :content "yo"}]
          :stream false
          :format "json"
          :options {:seed 1 :temperature 0}
          :keep_alive 0}
         (llm/chat-body {:keep-alive 0}
                        {:model "m" :format "json" :temperature 0 :options {:seed 1}
                         :messages [{:role "user" :content "hi" :images ["aa=="]}
                                    {:role "assistant" :content "yo" :images []}]}))))

;; ---------------------------------------------------------------------------
;; Preflight

(deftest preflight-reports-version-and-models
  (let [r (llm/preflight (provider))]
    (is (true? (:ok r)))
    (is (= "0.12.6" (:version r)))
    (is (= ["llama3.2:latest" "llava:latest"] (:models r)))
    (is (not (contains? r :model-available?)) "no configured model, nothing to check")
    (is (= [[:get "/api/version"] [:get "/api/tags"]] (mapv (juxt :method :uri) (received)))))
  (testing "the configured model is looked up, with or without :latest"
    (is (true? (:model-available? (llm/preflight (provider {:model "llava"})))))
    (is (true? (:model-available? (llm/preflight (provider {:model "llama3.2:latest"})))))
    (is (false? (:model-available? (llm/preflight (provider {:model "gemma4"})))))))

(deftest preflight-never-throws-for-a-dead-provider
  (let [r (llm/preflight (llm/ollama {:url (str "http://127.0.0.1:" (closed-port)) :timeout-ms 2000}))]
    (is (false? (:ok r)))
    (is (= :llm/unreachable (get-in r [:error :type])))))

;; ---------------------------------------------------------------------------
;; The runtime's :llm function

(deftest chat-fn-uses-the-configuration
  (let [cfg (assoc-in config/defaults [:llm :ollama] {:url (url) :model "plain" :timeout-ms 5000 :keep-alive "10m"})
        f (llm/chat-fn cfg)
        ctx {:module/id :echo}]
    (testing "the configured model is the default"
      (let [reply (f ctx {:messages messages})]
        (is (= "plain" (:model reply)))
        (is (= "plain" (:model (:body (last-request)))))
        (is (= "10m" (:keep_alive (:body (last-request)))))))
    (testing "a request's model wins"
      (is (= {:age 22 :available false}
             (:json (f ctx {:messages messages :model "structured" :format {"type" "object"}})))))
    (testing "the configured timeout applies"
      (let [f2 (llm/chat-fn (assoc-in cfg [:llm :ollama :timeout-ms] 300))
            data (thrown-data #(f2 ctx {:messages messages :model "slow"}))]
        (is (= :llm/timeout (:type data)))
        (is (= 300 (:timeout-ms data))))
      (await-record! "slow"))))

(deftest chat-fn-refuses-another-provider
  (is (= :llm/invalid-config
         (:type (thrown-data #(llm/chat-fn (assoc-in config/defaults [:llm :provider] :openai)))))))

(deftest ollama-constructor-checks-its-options
  (is (= :llm/invalid-config (:type (thrown-data #(llm/ollama {:url "nope"})))))
  (is (= :llm/invalid-config (:type (thrown-data #(llm/ollama {:url (url) :timeout-ms 0})))))
  (is (= :llm/invalid-config (:type (thrown-data #(llm/ollama {:url (url) :model ""})))))
  (testing "defaults come from the hub configuration"
    (let [p (llm/ollama {:url (url)})]
      (is (= 120000 (:timeout-ms p)))
      (is (= "5m" (:keep-alive p)))
      (is (nil? (:model p))))))
