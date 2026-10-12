;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.audit-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.audit :as audit]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.store :as store]
            [clogem.hub.log :as log]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 10) (recur))))))

(deftest rows-land-through-the-single-writer-without-blocking-the-caller
  (let [dir (fs/create-temp-dir {:prefix "clogem-audit"})
        b (bus/new-bus)
        s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})
        au (audit/start! s {:queue 8})]
    (try
      (is (nil? (audit/record! nil {:method "tools/call"})) "no audit, no-op")
      (audit/record! au {:client "llm" :profile :llm-large :method "tools/call" :name "echo_say" :ok? true :duration-ms 12 :request-id 7})
      (audit/record! au {:client "-" :profile :none :method "?" :ok? false :error "unauthorized" :duration-ms 0})
      (audit/record! au {:client "gui" :profile :gui :method "resources/read" :name "clogem://echo/last/said" :ok? false
                         :error (apply str (repeat 500 "x")) :request-id "abc"})
      (is (wait-for #(= 3 (:recorded @(:stats au))) 10000))
      (let [rows (audit/recent s 10)]
        (is (= ["resources/read" "?" "tools/call"] (map :method rows)) "newest first")
        (is (= [{:client "llm" :profile "llm-large" :name "echo_say" :ok 1 :error nil :duration_ms 12 :request_id "7"}]
               (map #(select-keys % [:client :profile :name :ok :error :duration_ms :request_id]) (filter #(= "tools/call" (:method %)) rows))))
        (is (= 200 (count (:error (first rows)))) "error text is capped")
        (is (= "abc" (:request_id (first rows))))
        (is (every? #(pos? (:ts %)) rows)))
      (testing "the table never receives arguments, results or tokens: the row shape has no column for them"
        (is (= #{:id :ts :client :profile :method :name :ok :error :duration_ms :request_id}
               (set (keys (first (audit/recent s 1)))))))
      (testing "stop! drains what was queued"
        (dotimes [i 5] (audit/record! au {:client "c" :profile :gui :method "tools/call" :name (str "t" i) :ok? true}))
        (audit/stop! au)
        (is (= 8 (-> (store/query s "SELECT count(*) AS n FROM audit") first :n))))
      (finally (store/close! s) (bus/stop! b) (fs/delete-tree dir)))))
