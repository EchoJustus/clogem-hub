;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.sqlite-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.log :as log]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- temp-db []
  (let [dir (fs/create-temp-dir {:prefix "clogem-sqlite"})]
    [dir (str (fs/path dir "t.db"))]))

(deftest the-pod-semantics-the-writer-relies-on
  (let [[dir db] (temp-db)]
    (try
      (is (= [{:journal_mode "wal"}] (do (sqlite/execute! db "PRAGMA journal_mode=WAL") (sqlite/query db "PRAGMA journal_mode")))
          "WAL is persistent across connections")
      (is (= [{:foreign_keys 0}] (sqlite/query db "PRAGMA foreign_keys")) "flag pragmas are per connection: off again")
      (is (= [{:foreign_keys 1}] (sqlite/query db "PRAGMA foreign_keys=ON; PRAGMA foreign_keys")) "a preamble applies within the call")
      (sqlite/execute! db "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
      (testing "a batch without BEGIN/COMMIT is not atomic"
        (is (thrown? clojure.lang.ExceptionInfo (sqlite/execute! db "INSERT INTO t (v) VALUES ('a'); INSERT INTO nope VALUES (1)")))
        (is (= [{:n 1}] (sqlite/query db "SELECT count(*) AS n FROM t"))))
      (testing "an explicit transaction rolls back when a statement fails"
        (is (thrown? clojure.lang.ExceptionInfo (sqlite/execute! db "BEGIN IMMEDIATE; INSERT INTO t (v) VALUES ('b'); INSERT INTO nope VALUES (1); COMMIT")))
        (is (= [{:n 1}] (sqlite/query db "SELECT count(*) AS n FROM t"))))
      (testing "parameters are consumed across the statements of a batch"
        (is (= {:rows-affected 1 :last-inserted-id 3}
               (sqlite/execute! db ["BEGIN IMMEDIATE; INSERT INTO t (v) VALUES (?); INSERT INTO t (v) VALUES (?); COMMIT" "x" "y"])))
        (is (= [{:v "x"} {:v "y"}] (sqlite/query db ["SELECT v FROM t WHERE v IN (?, ?) ORDER BY id" "x" "y"]))))
      (testing "query steps only the last statement; query_only applies at prepare time"
        (is (= [] (sqlite/query db "PRAGMA query_only=ON; INSERT INTO t (v) VALUES ('ro')")) "the write is refused silently")
        (is (= [{:n 3}] (sqlite/query db "SELECT count(*) AS n FROM t")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"readonly" (sqlite/execute! db "PRAGMA query_only=ON; INSERT INTO t (v) VALUES ('ro')"))))
      (testing "errors are classified"
        (let [e (try (sqlite/query db "SELECT * FROM nope") (catch clojure.lang.ExceptionInfo e e))]
          (is (= :sql-error (:type (ex-data e))))
          (is (re-find #"no such table" (ex-message e)))))
      (testing "blank SQL never reaches the pod"
        (doseq [bad ["" "   " [" " 1] nil 42 [nil]]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank" (sqlite/query db bad)) (pr-str bad))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-blank" (sqlite/execute! db bad)) (pr-str bad))))
      (testing "value types round-trip"
        (sqlite/execute! db "CREATE TABLE ty (k, b, n, d, l, bl)")
        (sqlite/execute! db ["INSERT INTO ty VALUES (?,?,?,?,?,?)" :kw true nil 1.5 42 (byte-array [1 2 3])])
        (is (= [{:k "kw" :b 1 :n nil :d 1.5 :l 42 :bl [1 2 3]}] (sqlite/query db "SELECT * FROM ty"))
            "keywords become their name, booleans integers, blobs byte vectors"))
      (finally (fs/delete-tree dir)))))

(deftest a-hung-or-dead-pod-is-replaced
  (let [[dir db] (temp-db)
        before (sqlite/stats)]
    (try
      (sqlite/execute! db "CREATE TABLE t (id INTEGER PRIMARY KEY)")
      (testing "the watchdog restarts the pod and the next call works"
        ;; a whitespace-only statement makes the pod hang, so bypass the SQL
        ;; validation to provoke it
        (with-redefs [sqlite/validate-sql! identity]
          (let [e (try (sqlite/query db "   " {:timeout-ms 500}) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :pod-timeout (:type (ex-data e))))
            (is (= 500 (:timeout-ms (ex-data e))))))
        (is (= [{:n 0}] (sqlite/query db "SELECT count(*) AS n FROM t")))
        (is (= {:rows-affected 1 :last-inserted-id 1} (sqlite/execute! db "INSERT INTO t DEFAULT VALUES")))
        (let [after (sqlite/stats)]
          (is (= (inc (:timeouts before)) (:timeouts after)))
          (is (= (inc (:restarts before)) (:restarts after)))
          (is (= (inc (:generation before)) (:generation after)))))
      (finally (fs/delete-tree dir)))))

(deftest concurrent-calls-are-serialized-and-all-answered
  (let [[dir db] (temp-db)]
    (try
      (sqlite/execute! db "PRAGMA journal_mode=WAL")
      (sqlite/execute! db "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
      (let [writers (for [th (range 4)]
                      (future (dotimes [i 25]
                                (sqlite/execute! db ["PRAGMA busy_timeout=5000; BEGIN IMMEDIATE; INSERT INTO t (v) VALUES (?); COMMIT" (str th "-" i)]))))
            readers (for [_ (range 4)]
                      (future (dotimes [_ 25] (sqlite/query db "PRAGMA query_only=ON; SELECT count(*) AS n FROM t"))))
            results (mapv #(deref % 60000 :timeout) (doall (concat writers readers)))]
        (is (not-any? #{:timeout} results) "no call hangs: the gate serializes the pod")
        (is (= [{:n 100}] (sqlite/query db "SELECT count(*) AS n FROM t")))
        (is (= 0 (:busy (sqlite/stats))) "zero SQLITE_BUSY"))
      (finally (fs/delete-tree dir)))))
