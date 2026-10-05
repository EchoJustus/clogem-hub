;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.store-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.migrate :as migrate]
            [clogem.hub.db.sql :as sql]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.db.store :as store]
            [clogem.hub.db.writer :as writer]
            [clogem.hub.log :as log]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- with-store
  "Open a store in a temp directory on a fresh bus; (f store bus dir)."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-store"})
        b (bus/new-bus)
        s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})]
    (try (f s b dir)
         (finally (store/close! s) (bus/stop! b) (fs/delete-tree dir)))))

(defn- count-of [s table]
  (-> (store/query s (str "SELECT count(*) AS n FROM " table)) first :n))

(deftest open-sets-wal-once-and-applies-the-hub-schema
  (with-store
    (fn [s b dir]
      (is (fs/exists? (:path s)))
      (is (= [{:journal_mode "wal"}] (sqlite/query (:path s) "PRAGMA journal_mode")) "WAL persists in the file")
      (is (= [{:module "hub" :version 1 :name "0001-jobs.sql"}]
             (store/query s "SELECT module, version, name FROM schema_migrations ORDER BY module, version")))
      (is (= [{:name "jobs"}] (store/query s "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'jobs'")))
      (is (= [:db/tx] (bus/owners b)) "the writer owns :db/tx")
      (is (or (nil? (:fstype s)) (string? (:fstype s))))
      (testing "reopening applies nothing twice"
        (let [b2 (bus/new-bus)
              s2 (store/open! {:path (str (fs/path dir "second.db")) :bus b2})]
          (try
            (is (= 1 (count-of s2 "schema_migrations")))
            (store/close! s2)
            (let [s3 (store/open! {:path (str (fs/path dir "second.db")) :bus b2})]
              (is (= 1 (count-of s3 "schema_migrations")))
              (is (= {:module :hub :applied [] :current 1}
                     (migrate/migrate! {:db (:path s3) :submit #(writer/submit! (:writer s3) %)} :hub store/hub-migrations)))
              (store/close! s3))
            (finally (bus/stop! b2)))))
      (is (= #{:path :fstype :writer} (set (keys (store/stats s))))))))

(deftest unsafe-paths-are-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Windows mount" (store/check-path! "/mnt/d/media/clogem.db")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Windows mount"
                        (store/check-path! (str (fs/path (System/getProperty "user.home") "RUN" "clogem.db")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"9p"
                        (with-redefs [store/filesystem-type (constantly "9p")] (store/check-path! "/tmp/x/clogem.db"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"drvfs"
                        (with-redefs [store/filesystem-type (constantly "drvfs")] (store/check-path! "/tmp/x/clogem.db"))))
  (is (nil? (with-redefs [store/filesystem-type (constantly nil)] (store/check-path! "/tmp/x/clogem.db")))
      "an unknown filesystem type is allowed with a warning")
  (is (= "ext4" (with-redefs [store/filesystem-type (constantly "ext4")] (store/check-path! "/tmp/x/clogem.db")))))

(deftest transactions-run-through-the-single-writer
  (with-store
    (fn [s b _]
      (is (= {:ok true :result {:rows-affected 0 :last-inserted-id 0 :statements 1}}
             (store/tx! s :echo ["CREATE TABLE echo_items (id INTEGER PRIMARY KEY, k TEXT NOT NULL, v TEXT)"])))
      (is (= {:ok true :result {:rows-affected 1 :last-inserted-id 2 :statements 2}}
             (store/tx! s :echo [["INSERT INTO echo_items (k, v) VALUES (?, ?)" "a" "1"]
                                 ["INSERT INTO echo_items (k, v) VALUES (?, ?);" "b" "2"]]))
          "parameters follow their statements; a trailing semicolon is fine")
      (is (= [{:k "a" :v "1"} {:k "b" :v "2"}] (store/query s ["SELECT k, v FROM echo_items WHERE k IN (?, ?) ORDER BY id" "a" "b"])))
      (testing "the bus command is the same door"
        (is (= {:ok true :result {:rows-affected 1 :last-inserted-id 3 :statements 1}}
               (bus/request! b {:command :db/tx :module :echo :statements [["INSERT INTO echo_items (k) VALUES (?)" "c"]]}))))
      (testing "a failing statement rolls the transaction back"
        (let [reply (store/tx! s :echo [["INSERT INTO echo_items (k) VALUES (?)" "d"]
                                        "INSERT INTO echo_items (k) VALUES (NULL)"])]
          (is (false? (:ok reply)))
          (is (= :sql-error (get-in reply [:error :type])))
          (is (re-find #"NOT NULL" (get-in reply [:error :message]))))
        (is (= 3 (count-of s "echo_items")) "d was not kept"))
      (testing "foreign keys are enforced inside every transaction"
        (store/tx! s :echo ["CREATE TABLE echo_parent (id INTEGER PRIMARY KEY)"
                            "CREATE TABLE echo_child (id INTEGER PRIMARY KEY, parent INTEGER NOT NULL REFERENCES echo_parent(id))"])
        (is (re-find #"FOREIGN KEY" (get-in (store/tx! s :echo ["INSERT INTO echo_child (parent) VALUES (999)"]) [:error :message]))))
      (testing "malformed transactions are rejected before reaching the pod"
        (let [calls (:calls (sqlite/stats))
              rejected (fn [statements]
                         (let [r (store/tx! s :echo statements)]
                           (is (= :invalid-tx (get-in r [:error :type])) (pr-str statements))
                           (get-in r [:error :problems])))]
          (is (= [":statements is empty"] (rejected [])))
          (is (= [":statements must be a vector"] (rejected "DELETE FROM echo_items")))
          (is (= ["statement 0 is blank"] (rejected ["  "])))
          (is (= ["statement 0 must be a string or [sql & params]"] (rejected [42])))
          (is (= ["statement 1 holds more than one statement; ';' may only end it"]
                 (rejected ["DELETE FROM echo_items WHERE k = 'zz'" "SELECT 1; PRAGMA journal_mode=DELETE"])))
          (is (= ["statement 0 may not start with PRAGMA"] (rejected ["PRAGMA foreign_keys=OFF"])))
          (is (= ["statement 0 may not start with BEGIN"] (rejected ["  -- sneaky\n BEGIN"])))
          (is (= ["statement 0 may not start with ATTACH"] (rejected ["/* c */ attach database 'x' as y"])))
          (is (= calls (:calls (sqlite/stats))) "no pod call was made")
          (is (= 8 (:rejected (writer/stats (:writer s)))))))
      (let [st (writer/stats (:writer s))]
        (is (= 5 (:committed st)))
        (is (= 2 (:failed st)))))))

(deftest queries-are-single-read-only-statements
  (with-store
    (fn [s _ _]
      (store/tx! s :echo ["CREATE TABLE echo_q (id INTEGER PRIMARY KEY, v TEXT)" ["INSERT INTO echo_q (v) VALUES (?)" "x"]])
      (is (= [{:id 1 :v "x"}] (store/query s "SELECT * FROM echo_q;")))
      (is (= [{:v "x"}] (store/query s ["select v from echo_q where id = ?" 1])) "case does not matter")
      (is (= [{:n 1}] (store/query s "WITH c AS (SELECT count(*) AS n FROM echo_q) SELECT n FROM c")))
      (is (= [{:column1 1}] (store/query s "VALUES (1)")))
      (is (seq (store/query s "EXPLAIN SELECT 1")))
      (let [invalid (fn [q] (let [e (try (store/query s q) (catch clojure.lang.ExceptionInfo e e))]
                              (is (= :invalid-query (:type (ex-data e))) (pr-str q))
                              (first (:problems (ex-data e)))))]
        (is (re-find #"must start with" (invalid "DELETE FROM echo_q")))
        (is (re-find #"must start with" (invalid "PRAGMA query_only=OFF")))
        (is (re-find #"more than one statement" (invalid "SELECT 1; PRAGMA query_only=OFF; DELETE FROM echo_q")))
        (is (re-find #"is blank" (invalid "  ")))
        (is (re-find #"must be a string" (invalid [1 2]))))
      (is (= [{:v "x"}] (store/query s "SELECT v FROM echo_q")) "nothing was deleted")
      (testing "SQL errors surface as exceptions"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such table" (store/query s "SELECT * FROM nope"))))
      (testing "the word checks see through comments"
        (is (= "SELECT" (sql/leading-word "-- hi\n/* there */  select 1")))
        (is (= "" (sql/leading-word "-- only a comment")))
        (is (sql/single-statement? "SELECT 1;;  "))
        (is (not (sql/single-statement? "SELECT 1; SELECT 2")))))))

(defn- write-migration! [path content]
  (fs/create-dirs (fs/parent path))
  (spit path content)
  path)

(deftest module-migrations-apply-once-in-order-with-prefixed-objects
  (with-store
    (fn [s _ dir]
      (let [cp-root (str (fs/path dir "mig"))
            res (str cp-root "/m")]
        (write-migration! (str res "/good/0001-init.sql")
                          "-- first\nCREATE TABLE echo_notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL);\nCREATE INDEX echo_notes_body ON echo_notes (body);\n")
        (write-migration! (str res "/good/0002-data.sql") "INSERT INTO echo_notes (body) VALUES ('seed');")
        (write-migration! (str res "/good/README.md") "ignored")
        (is (= {:module :echo :applied [1 2] :current 2} (store/migrate! s :echo (str res "/good"))))
        (is (= [{:body "seed"}] (store/query s "SELECT body FROM echo_notes")))
        (is (= {:module :echo :applied [] :current 2} (store/migrate! s :echo (str res "/good"))) "idempotent")
        (write-migration! (str res "/good/0003-more.sql") "INSERT INTO echo_notes (body) VALUES ('third');")
        (is (= {:module :echo :applied [3] :current 3} (store/migrate! s :echo (str res "/good"))))
        (is (= [{:n 2}] (store/query s "SELECT count(*) AS n FROM echo_notes")))
        (is (= [1 2 3] (map :version (store/query s "SELECT version FROM schema_migrations WHERE module = 'echo' ORDER BY version"))))
        (testing "an unprefixed object is rejected before anything runs"
          ;; versions 1-3 of :echo are applied already, so the new files use 4 and 5
          (write-migration! (str res "/bad/0004-bad.sql") "CREATE TABLE echo_ok (id INTEGER PRIMARY KEY);\nCREATE TABLE notes (id INTEGER PRIMARY KEY);")
          (let [e (try (store/migrate! s :echo (str res "/bad")) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :migration-rejected (:type (ex-data e))))
            (is (= ["object notes must be prefixed echo_"] (:problems (ex-data e)))))
          (is (empty? (store/query s "SELECT name FROM sqlite_master WHERE name IN ('echo_ok', 'notes')"))))
        (testing "transaction control in a migration is rejected"
          (write-migration! (str res "/tc/0005-tc.sql") "BEGIN; CREATE TABLE echo_tc (id INTEGER PRIMARY KEY); COMMIT;")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"may not start with BEGIN" (store/migrate! s :echo (str res "/tc")))))
        (testing "a failing migration leaves no trace of itself"
          (write-migration! (str res "/fail/0001-ok.sql") "CREATE TABLE echo_f1 (id INTEGER PRIMARY KEY);")
          (write-migration! (str res "/fail/0002-boom.sql") "CREATE TABLE echo_f2 (id INTEGER PRIMARY KEY);\nINSERT INTO echo_nope VALUES (1);")
          (let [e (try (store/migrate! s :other (str res "/fail")) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :migration-rejected (:type (ex-data e))) "echo_ tables do not belong to :other"))
          (write-migration! (str res "/fail2/0001-ok.sql") "CREATE TABLE other_f1 (id INTEGER PRIMARY KEY);")
          (write-migration! (str res "/fail2/0002-boom.sql") "CREATE TABLE other_f2 (id INTEGER PRIMARY KEY);\nINSERT INTO other_nope VALUES (1);")
          (let [e (try (store/migrate! s :other (str res "/fail2")) (catch clojure.lang.ExceptionInfo e e))]
            (is (= :migration-failed (:type (ex-data e))))
            (is (= 2 (:version (ex-data e)))))
          (is (= [1] (map :version (store/query s "SELECT version FROM schema_migrations WHERE module = 'other'"))) "version 1 applied, 2 rolled back")
          (is (empty? (store/query s "SELECT name FROM sqlite_master WHERE name = 'other_f2'"))))
        (testing "duplicate versions and missing directories fail loudly"
          (write-migration! (str res "/dup/0001-a.sql") "SELECT 1")
          (write-migration! (str res "/dup/001-b.sql") "SELECT 1")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate" (store/migrate! s :echo (str res "/dup"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not found" (store/migrate! s :echo (str res "/missing")))))
        (testing "dashes in module ids become underscores in the prefix"
          (is (= "my_mod_" (migrate/table-prefix :my-mod)))
          (is (= ["object y must be prefixed my_mod_"]
                 (migrate/prefix-problems :my-mod "CREATE TABLE my_mod_x (a); CREATE UNIQUE INDEX IF NOT EXISTS y ON my_mod_x (a)")))
          (is (= [] (migrate/prefix-problems :hub "CREATE TABLE anything (a)"))))))))

(deftest a-thousand-concurrent-transactions-land-exactly-once-without-busy
  (with-store
    (fn [s _ _]
      (store/tx! s :echo ["CREATE TABLE echo_c (id INTEGER PRIMARY KEY, v INTEGER NOT NULL)"])
      (let [busy-before (:busy (sqlite/stats))
            t0 (System/nanoTime)
            replies (->> (range 1000)
                         (mapv (fn [i] (future (store/tx! s :echo [["INSERT INTO echo_c (v) VALUES (?)" i]]))))
                         (mapv #(deref % 120000 {:ok false :error {:type :test-timeout}})))
            ms (quot (- (System/nanoTime) t0) 1000000)]
        (is (every? :ok replies) (pr-str (frequencies (map (comp :type :error) (remove :ok replies)))))
        (is (= 1000 (count-of s "echo_c")) "exactly one row per request")
        (is (= [{:n 1000}] (store/query s "SELECT count(DISTINCT v) AS n FROM echo_c")))
        (is (= busy-before (:busy (sqlite/stats))) "zero SQLITE_BUSY")
        (is (= 1002 (:committed (writer/stats (:writer s)))) "hub migration + CREATE TABLE + 1000")
        (log/info {:msg "1000 concurrent transactions" :ms ms})
        (is (< ms 60000))))))

(deftest killing-the-process-mid-transaction-leaves-the-database-consistent
  (let [dir (fs/create-temp-dir {:prefix "clogem-kill"})
        db (str (fs/path dir "clogem.db"))
        err (fs/file (fs/path dir "child.err"))
        proc (p/process {:err err :dir (System/getProperty "user.dir")}
                        "bb" "test/fixtures/db/long_tx_child.clj" db "6000000")
        ^java.lang.Process jproc (:proc proc)
        reader (io/reader (:out proc))
        ready (future (loop [] (when-let [line (.readLine ^java.io.BufferedReader reader)]
                                 (if (= "READY" line) :ready (recur)))))]
    (try
      (is (= :ready (deref ready 90000 :timeout)) "the child committed its first rows")
      (Thread/sleep 1200)
      (is (.isAlive jproc) "the child is still inside its big transaction")
      (let [handle (.toHandle jproc)
            pods (vec (iterator-seq (.iterator (.descendants handle))))]
        (is (seq pods) "the child has a pod process")
        (doseq [^java.lang.ProcessHandle d pods] (.destroyForcibly d))
        (.destroyForcibly jproc)
        (.waitFor jproc 30 java.util.concurrent.TimeUnit/SECONDS))
      (let [b (bus/new-bus)
            s (store/open! {:path db :bus b})]
        (try
          (is (= [{:integrity_check "ok"}] (sqlite/query db "PRAGMA integrity_check")) "pragmas are not module queries; ask the gateway")
          (is (= 5 (count-of s "kill_t")) "only the committed rows survive; the interrupted transaction left nothing")
          (is (:ok (store/tx! s :hub ["INSERT INTO kill_t (v) VALUES (1)"])) "the file is writable again")
          (is (= 6 (count-of s "kill_t")))
          (finally (store/close! s) (bus/stop! b))))
      (finally
        (when (.isAlive jproc) (.destroyForcibly jproc))
        (fs/delete-tree dir)))))
