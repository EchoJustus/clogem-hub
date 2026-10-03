;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.methods-test
  "Golden fixtures in test/fixtures/jsonrpc: each file holds a request, the
   expected response (a subset that must match exactly, null for none) and
   optionally the HTTP status the method layer must demand and the tool names
   a list must return."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.jsonrpc :as rpc]
            [clogem.hub.mcp.methods :as methods]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.module.echo.core]
            [clogem.module.echo.tools]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- deps []
  (let [rt (runtime/new-runtime config/defaults)
        reg (registry/new-registry {:runtime rt})]
    (runtime/attach-registry! rt reg)
    (registry/register! reg (manifest/read-manifest (io/resource "clogem/module/system/manifest.edn")))
    (registry/register! reg (manifest/read-manifest (io/resource "clogem/module/echo/manifest.edn")))
    {:registry reg :profile :admin}))

(defn- subset?
  "True when every key of `expected` is present in `actual` with an equal (or
   recursively subset) value. Vectors compare element-wise."
  [expected actual]
  (cond
    (map? expected) (and (map? actual)
                         (every? (fn [[k v]] (and (contains? actual k) (subset? v (get actual k)))) expected))
    (vector? expected) (and (sequential? actual) (= (count expected) (count actual))
                            (every? true? (map subset? expected actual)))
    :else (= expected actual)))

(defn- roundtrip
  "JSON-encode then decode, so fixtures compare against what a client sees."
  [v]
  (json/parse-string (json/generate-string v) true))

(deftest golden-fixtures
  (let [deps (deps)
        dispatch (methods/dispatcher deps)
        files (sort (map str (fs/glob "test/fixtures/jsonrpc" "*.json")))]
    (is (<= 20 (count files)))
    (doseq [file files]
      (let [{:keys [request expect expect_tool_names http_status]} (json/parse-string (slurp file) true)
            outcome (rpc/handle request dispatch)
            actual (roundtrip (:response outcome))]
        (testing (fs/file-name file)
          (if (nil? expect)
            (is (nil? actual) (pr-str actual))
            (is (subset? (roundtrip expect) actual) (str "expected subset " (pr-str expect) "\n  actual " (pr-str actual))))
          (when http_status
            (is (= http_status (:http-status outcome)) "HTTP status demanded by the method layer"))
          (when-not http_status
            (is (nil? (:http-status outcome)) "no HTTP status override for ordinary responses"))
          (when expect_tool_names
            (is (= expect_tool_names (mapv :name (get-in actual [:result :tools]))))))))))

(deftest tool-objects-follow-the-spec
  (let [tools (get-in (methods/list-tools (deps) {}) [:tools])
        health (first (filter #(= "system_health" (:name %)) tools))
        say (first (filter #(= "echo_say" (:name %)) tools))]
    (is (= {:type "object" :additionalProperties false} (:inputSchema health))
        "a no-parameter tool uses the recommended empty object schema")
    (is (= {:readOnlyHint true :idempotentHint true} (:annotations health)))
    (is (= "object" (get-in say [:inputSchema :type])))
    (is (= false (get-in say [:inputSchema :additionalProperties])))
    (is (= [:text] (get-in say [:inputSchema :required])))
    (is (nil? (:_meta say)) "an empty :meta is not emitted")
    (is (every? #(nil? (get-in % [:inputSchema :$schema])) tools) "2020-12 is the default dialect")))

(deftest tool-failures-are-results-not-protocol-errors
  ;; Handlers are resolved when a module registers, so the redefinition must
  ;; be in place before the registry is built.
  (testing "a throwing handler"
    (with-redefs [clogem.module.echo.tools/say (fn [_ _] (throw (ex-info "kaboom" {})))]
      (let [r (methods/call-tool (deps) {:name "echo_say" :arguments {:text "x"}})]
        (is (true? (:isError r)))
        (is (= "complete" (:resultType r)))
        (is (re-find #"kaboom" (get-in r [:content 0 :text]))))))
  (testing "a result that violates the output schema"
    (with-redefs [clogem.module.echo.tools/say (fn [_ _] {:text 42})]
      (let [r (methods/call-tool (deps) {:name "echo_say" :arguments {:text "x"}})]
        (is (true? (:isError r)))
        (is (re-find #"output schema" (get-in r [:content 0 :text]))))))
  (testing "non-object arguments are a protocol error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arguments must be an object"
                          (methods/call-tool (deps) {:name "echo_say" :arguments [1]})))))

(deftest era-detection
  (is (= :modern (methods/era {:params {:_meta {methods/meta-protocol-version "2026-07-28"}}})))
  (is (= :legacy (methods/era {:method "initialize" :params {:protocolVersion "2025-11-25"}})))
  (is (= :legacy (methods/era {:method "ping"}))))
