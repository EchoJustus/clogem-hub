;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.runtime-test
  "clogem.api end to end: a module's ctx against the real bus and store."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.api :as api]
            [clogem.hub.bus :as bus]
            [clogem.hub.config :as config]
            [clogem.hub.db.store :as store]
            [clogem.hub.log :as log]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.module.echo.core]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def echo-manifest
  (-> (manifest/read-manifest (io/resource "clogem/module/echo/manifest.edn"))
      (assoc-in [:bus :subscribes] #{:job/progress :registry/changed})))

(def system-manifest (manifest/read-manifest (io/resource "clogem/module/system/manifest.edn")))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 5) (recur))))))

(defn- with-system [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-runtime"})
        b (bus/new-bus)
        s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})
        rt (runtime/new-runtime {:config config/defaults :bus b :store s})
        reg (registry/new-registry {:runtime rt :bus b})]
    (runtime/attach-registry! rt reg)
    (try (f reg)
         (finally (registry/stop-all! reg) (store/close! s) (bus/stop! b) (fs/delete-tree dir)))))

(deftest a-module-reaches-everything-through-clogem-api
  (with-system
    (fn [reg]
      (registry/register! reg system-manifest)
      (is (= :ready (:status (registry/register! reg echo-manifest))))
      (let [ctx (registry/module-ctx reg :echo echo-manifest)
            seen (atom [])]
        (testing "subscriptions declared in the manifest work and see registry changes"
          (api/subscribe! ctx :registry/changed #(swap! seen conj %))
          (api/subscribe! ctx :job/progress #(swap! seen conj %))
          (registry/unregister! reg :system)
          (is (wait-for #(= 1 (count @seen)) 2000))
          (is (= {:change :unregistered :module :system} (:event/payload (first @seen)))))
        (testing "commands reach their owner"
          (is (= {:ok true :result {:status :ok :modules [{:id :echo :status :ready :reason nil}]}}
                 (api/request! ctx {:command :system/health}))))
        (testing "transactions and queries"
          (is (:ok (api/submit-tx! ctx ["CREATE TABLE echo_words (id INTEGER PRIMARY KEY, w TEXT)"])))
          (is (:ok (api/submit-tx! ctx {:statements [["INSERT INTO echo_words (w) VALUES (?)" "hi"]
                                                     ["INSERT INTO echo_words (w) VALUES (?)" "there"]]})))
          (is (= [{:w "hi"} {:w "there"}] (api/query ctx "SELECT w FROM echo_words ORDER BY id")))
          (is (= :invalid-tx (get-in (api/submit-tx! ctx ["PRAGMA journal_mode=DELETE"]) [:error :type])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid query" (api/query ctx "DELETE FROM echo_words"))))
        (testing "jobs"
          (let [{:keys [result]} (api/job! ctx :create {:kind "index" :input {:n 2}})
                id (:id result)]
            (is (= :queued (:status result)))
            (is (= :running (get-in (api/job! ctx :progress {:id id :progress 0.5}) [:result :status])))
            (is (wait-for #(= 2 (count @seen)) 2000) "the module sees its own :job/progress event")
            (is (= id (get-in (second @seen) [:event/payload :id])))
            (is (= :done (get-in (api/job! ctx :complete {:id id :result :ok}) [:result :status])))
            (is (= [{:status "done"}] (api/query ctx ["SELECT status FROM jobs WHERE id = ?" id])))))
        (testing "config and log"
          (is (nil? (api/config ctx [:anything])))
          (is (nil? (api/log ctx :debug {:msg "fine"}))))))))

(deftest configuration-validation-covers-the-database
  (is (= (str (fs/path (config/data-dir) "clogem.db")) (config/db-path config/defaults)))
  (is (= "/x/y.db" (config/db-path {:db {:path "/x/y.db"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db path" (config/load-config {:db {:path ""}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db queue" (config/load-config {:db {:queue 0}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"request-timeout-ms" (config/load-config {:bus {:request-timeout-ms -1}})))
  (is (= 256 (get-in (config/load-config {}) [:db :queue]))))
