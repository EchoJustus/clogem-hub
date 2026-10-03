;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.log-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clogem.hub.log :as log]))

(defn- captured [f]
  (let [err (java.io.StringWriter.)
        out (java.io.StringWriter.)]
    (binding [*err* err *out* out]
      (f))
    {:err (str err) :out (str out)}))

(deftest one-edn-map-per-line-on-stderr-only
  (let [{:keys [err out]} (captured #(do (log/info {:msg "a"}) (log/warn {:msg "b" :n 1})))
        lines (str/split-lines err)]
    (is (= "" out) "stdout stays clean for protocol frames")
    (is (= 2 (count lines)))
    (let [m (edn/read-string (first lines))]
      (is (= "a" (:msg m)))
      (is (= :info (:level m)))
      (is (string? (:ts m))))))

(deftest secrets-are-redacted-at-any-depth
  (is (= {:token :redacted :nested {:api_key :redacted :ok 1} :list [{:Authorization :redacted}]}
         (log/redact {:token "ghp" :nested {:api_key "x" :ok 1} :list [{:Authorization "Bearer x"}]})))
  (let [{:keys [err]} (captured #(log/info {:msg "login" :token "abc123"}))]
    (is (not (str/includes? err "abc123")))
    (is (str/includes? err ":redacted"))))

(deftest levels-filter
  (let [{:keys [err]} (captured #(log/debug {:msg "quiet"}))]
    (is (= "" err) "debug is below the default :info level"))
  (log/set-level! :debug)
  (try
    (is (str/includes? (:err (captured #(log/debug {:msg "loud"}))) "loud"))
    (finally (log/set-level! :info)))
  (is (thrown? clojure.lang.ExceptionInfo (log/set-level! :verbose))))
