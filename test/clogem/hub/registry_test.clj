;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.registry-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.module.echo.core]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- resource-manifest [path]
  (manifest/read-manifest (io/resource path)))

(def system-manifest (resource-manifest "clogem/module/system/manifest.edn"))
(def echo-manifest (resource-manifest "clogem/module/echo/manifest.edn"))

(defn- fresh-registry
  "A registry wired to the S01 runtime, with change events recorded in `events`."
  [events]
  (let [rt (runtime/new-runtime config/defaults)
        reg (registry/new-registry {:runtime rt :on-change #(swap! events conj %)})]
    (runtime/attach-registry! rt reg)
    reg))

(deftest built-in-manifests-are-valid
  (is (= {:ok? true :problems []} (manifest/check system-manifest)))
  (is (= {:ok? true :problems []} (manifest/check echo-manifest))))

(deftest registering-modules-orders-and-projects-deterministically
  (let [events (atom [])
        reg (fresh-registry events)]
    (is (= :ready (:status (registry/register! reg system-manifest))))
    (is (= :ready (:status (registry/register! reg echo-manifest))))
    (is (= [:echo :system] (registry/module-ids reg)) "ordered by id, not registration order")
    (is (= {:status :ok :modules [{:id :echo :status :ready :reason nil}
                                  {:id :system :status :ready :reason nil}]}
           (registry/status reg)))
    (testing "tools per profile, ordered by module id then tool name"
      (is (= ["echo_say" "system_health" "system_list_modules"]
             (map (comp :name :tool) (registry/tools-for reg :admin))))
      (is (= ["echo_say" "system_health"]
             (map (comp :name :tool) (registry/tools-for reg :llm-small)))
          "system_list_modules is not exposed to :llm-small")
      (is (nil? (registry/find-tool reg :llm-small "system_list_modules")))
      (is (fn? (:handler (registry/find-tool reg :admin "system_health")))))
    (testing "handlers run with the module ctx and reach the registry through the runtime"
      (let [{:keys [handler ctx]} (registry/find-tool reg :admin "system_health")]
        (is (= {:status "ok" :modules [{:id "echo" :status "ready"} {:id "system" :status "ready"}]}
               (handler ctx {}))))
      (let [{:keys [handler ctx]} (registry/find-tool reg :admin "system_list_modules")
            result (handler ctx {})]
        (is (= ["echo" "system"] (map :id (:modules result))))
        (is (= [1 2] (map :tools (:modules result)))))
      (let [{:keys [handler ctx]} (registry/find-tool reg :admin "echo_say")]
        (is (= {:text "HI"} (handler ctx {:text "hi" :upcase true})))))
    (testing "resource index is empty but well-formed"
      (is (= {:resources {} :templates []} (registry/resource-index reg :admin))))
    (testing "change events were emitted in order"
      (is (= [[:registered :system :ready] [:registered :echo :ready]]
             (map (juxt :change :module :status) @events)))
      (is (every? #(= :registry/changed (:event/type %)) @events)))
    (testing "unregister stops and removes"
      (is (true? (registry/unregister! reg :echo)))
      (is (false? (registry/unregister! reg :echo)))
      (is (= [:system] (registry/module-ids reg)))
      (registry/stop-all! reg)
      (is (empty? (registry/module-ids reg))))))

(deftest failures-are-reported-as-statuses-with-reasons
  (let [reg (fresh-registry (atom []))]
    (testing "invalid manifest"
      (let [e (registry/register! reg (assoc echo-manifest :module/version "nope"))]
        (is (= :unavailable (:status e)))
        (is (= :invalid-manifest (get-in e [:reason :type])))
        (is (seq (get-in e [:reason :problems])))))
    (testing "entry symbol that does not resolve"
      (let [e (registry/register! reg (assoc echo-manifest :module/entry 'clogem.module.echo.core/missing))]
        (is (= :unavailable (:status e)))
        (is (= :entry-unresolved (get-in e [:reason :type])))))
    (testing "handler symbol that does not resolve"
      (let [e (registry/register! reg (assoc-in echo-manifest [:mcp :tools 0 :handler] 'clogem.module.echo.tools/missing))]
        (is (= :unavailable (:status e)))
        (is (= :handlers-unresolved (get-in e [:reason :type])))))
    (testing "start throws"
      (with-redefs [clogem.module.echo.core/module {:start (fn [_] (throw (ex-info "boom" {})))}]
        (let [e (registry/register! reg echo-manifest)]
          (is (= :unavailable (:status e)))
          (is (= {:type :start-failed :message "boom"} (:reason e))))))
    (testing "health not ok → degraded, still serving"
      (with-redefs [clogem.module.echo.core/module {:start (fn [_] {}) :health (fn [_] {:status :degraded :why "x"})}]
        (let [e (registry/register! reg echo-manifest)]
          (is (= :degraded (:status e)))
          (is (= :degraded (:status (registry/status reg))))
          (is (= ["echo_say"] (map (comp :name :tool) (registry/tools-for reg :admin)))))))
    (testing "unavailable modules expose no tools"
      (registry/register! reg (assoc echo-manifest :module/entry 'clogem.module.echo.core/missing))
      (is (empty? (registry/tools-for reg :admin))))))

(deftest registry-wide-uniqueness-is-enforced
  (let [reg (fresh-registry (atom []))
        other (-> echo-manifest
                  (assoc :module/id :other
                         :module/entry 'clogem.module.echo.core/module)
                  (assoc-in [:mcp :tools 0 :handler] 'clogem.module.echo.tools/say))]
    (registry/register! reg echo-manifest)
    ;; `other` fails its own semantic rules (entry outside its namespace) but the
    ;; registry-wide conflict on tool name echo_say is reported first.
    (let [e (registry/register! reg other)]
      (is (= :unavailable (:status e)))
      (is (= :conflict (get-in e [:reason :type])))
      (is (some #(re-find #"duplicate tool name across modules: echo_say" %)
                (get-in e [:reason :problems]))))
    (testing "re-registering the same module id replaces it without a conflict"
      (is (= :ready (:status (registry/register! reg echo-manifest)))))))

(deftest unsupported-runtime-operations-fail-clearly
  (let [reg (fresh-registry (atom []))
        ctx (registry/module-ctx reg :echo echo-manifest)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not available until S02"
                          (clogem.api/query ctx {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not available until S04"
                          (clogem.api/run-process! ctx ["true"])))
    (is (= {:ok false :error {:type :unknown-command :command :nope}}
           (clogem.api/request! ctx {:command :nope})))
    (is (= :echo/said (:event/type (clogem.api/publish! ctx :echo/said {:text "x"}))))
    (is (fn? (clogem.api/subscribe! ctx :echo/said identity)))))
