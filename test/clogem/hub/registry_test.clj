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
            [clogem.hub.bus :as bus]
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

(def ^:dynamic *bus* nil)

(use-fixtures :each (fn [f] (binding [*bus* (bus/new-bus)] (try (f) (finally (bus/stop! *bus*))))))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 5) (recur))))))

(defn- fresh-registry
  "A registry on a runtime without a database, with :registry/changed events
   recorded in `events` through the bus."
  ([events] (fresh-registry events {}))
  ([events opts]
   (let [rt (runtime/new-runtime {:config config/defaults :bus *bus*})
         reg (registry/new-registry (merge {:runtime rt :bus *bus*} opts))]
     (bus/subscribe! *bus* :registry/changed #(swap! events conj %))
     (runtime/attach-registry! rt reg)
     reg)))

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
    (testing "change events were published on the bus in order"
      (is (wait-for #(= 2 (count @events)) 2000))
      (is (= [[:registered :system :ready] [:registered :echo :ready]]
             (map (comp (juxt :change :module :status) :event/payload) @events)))
      (is (every? #(= [:registry/changed :hub] ((juxt :event/type :event/source) %)) @events)))
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

(deftest invalid-ids-never-poison-the-registry
  (let [reg (fresh-registry (atom []))]
    (registry/register! reg system-manifest)
    (doseq [bad [(dissoc echo-manifest :module/id) (assoc echo-manifest :module/id "echo")]]
      (let [e (registry/register! reg bad)]
        (is (= :unavailable (:status e)))
        (is (= :invalid-manifest (get-in e [:reason :type])))))
    (is (= [:system] (registry/module-ids reg)))
    (is (= :ok (:status (registry/status reg))))
    (is (= ["system_health" "system_list_modules"] (map (comp :name :tool) (registry/tools-for reg :admin))))
    (is (= ["system"] (map :id (registry/manifest-summaries reg))))))

(deftest replacing-and-failing-modules-are-stopped
  (let [reg (fresh-registry (atom []))
        starts (atom 0) stops (atom 0)]
    (with-redefs [clogem.module.echo.core/module {:start (fn [_] (swap! starts inc) {})
                                                  :stop (fn [_] (swap! stops inc))
                                                  :health (fn [_] {:status :ok})}]
      (registry/register! reg echo-manifest)
      (registry/register! reg echo-manifest)
      (is (= [2 1] [@starts @stops]) "re-registering stops the previous instance")
      (registry/unregister! reg :echo)
      (is (= 2 @stops)))
    (with-redefs [clogem.module.echo.core/module {:start (fn [_] {}) :health (fn [_] (throw (ex-info "sick" {})))}]
      (let [e (registry/register! reg echo-manifest)]
        (is (= :degraded (:status e)) "a throwing health check degrades, it does not fail the start")
        (is (= "sick" (get-in e [:reason :health :error])))))))

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

(deftest before-start-failures-make-a-module-unavailable
  (let [calls (atom [])
        reg (fresh-registry (atom []) {:before-start (fn [m] (swap! calls conj (:module/id m))
                                                      (when (= :echo (:module/id m)) (throw (ex-info "migration broke" {:type :migration-failed}))))})]
    (is (= :ready (:status (registry/register! reg system-manifest))))
    (let [e (registry/register! reg echo-manifest)]
      (is (= :unavailable (:status e)))
      (is (= {:type :before-start-failed :message "migration broke"} (:reason e))))
    (is (= [:system :echo] @calls) "the hook runs once per registration, before start")
    (is (= ["system_health" "system_list_modules"] (map (comp :name :tool) (registry/tools-for reg :admin))))))

(deftest runtime-operations-without-a-database-fail-clearly
  (let [reg (fresh-registry (atom []))
        ctx (registry/module-ctx reg :echo echo-manifest)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"database is not available"
                          (clogem.api/query ctx "SELECT 1")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"database is not available"
                          (clogem.api/job! ctx :create {:kind "x"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not available until S04"
                          (clogem.api/run-process! ctx ["true"])))
    (is (= {:ok false :error {:type :unknown-command :command :nope}}
           (clogem.api/request! ctx {:command :nope})))
    (is (= :echo/said (:event/type (clogem.api/publish! ctx :echo/said {:text "x"}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not declare :echo/other"
                          (clogem.api/publish! ctx :echo/other {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match its declared schema"
                          (clogem.api/publish! ctx :echo/said {:text 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not declare :echo/said in :bus/subscribes"
                          (clogem.api/subscribe! ctx :echo/said identity)))
    (is (fn? (clogem.api/subscribe! (registry/module-ctx reg :echo (assoc-in echo-manifest [:bus :subscribes] #{:echo/said}))
                                    :echo/said identity)))))
