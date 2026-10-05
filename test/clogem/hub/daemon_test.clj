;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.daemon-test
  "The daemon as a process: a SIGTERM while transactions are queued must
   drain them before the pod goes away."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.store :as store]
            [clogem.hub.log :as log]))

(deftest sigterm-drains-the-writer-before-the-pod-dies
  (log/set-level! :error)
  (let [dir (fs/create-temp-dir {:prefix "clogem-sigterm"})
        err (fs/file (fs/path dir "child.err"))
        n 12
        proc (p/process {:err err :dir (System/getProperty "user.dir")}
                        "bb" "test/fixtures/db/daemon_sigterm_child.clj" (str dir) (str n))
        ^java.lang.Process jproc (:proc proc)
        reader (io/reader (:out proc))
        ready (future (loop [] (when-let [line (.readLine ^java.io.BufferedReader reader)]
                                 (if (= "READY" line) :ready (recur)))))]
    (try
      (is (= :ready (deref ready 90000 :timeout)) "the child started and queued its transactions")
      (let [t0 (System/nanoTime)]
        (.destroy jproc) ; SIGTERM
        (is (.waitFor jproc 60 java.util.concurrent.TimeUnit/SECONDS) "the child exited")
        (let [ms (quot (- (System/nanoTime) t0) 1000000)]
          (is (= 0 (.exitValue jproc)) "a handled signal exits 0")
          (is (< ms 25000) (str "shutdown took " ms " ms: the watchdog did not have to fire"))))
      (let [lines (remove str/blank? (str/split-lines (slurp err)))
            parsed (map #(try (edn/read-string %) (catch Exception _ ::not-edn)) lines)]
        (is (every? map? parsed) (str "stderr carries only EDN log lines, got: " (pr-str (take 3 (remove map? parsed)))))
        (is (not-any? #(= "restarting the sqlite pod" (:msg %)) (filter map? parsed)) "no pod restart during shutdown")
        (is (some #(= "signal received" (:msg %)) (filter map? parsed)))
        (is (some #(= "daemon stopped" (:msg %)) (filter map? parsed))))
      (let [b (bus/new-bus)
            s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})]
        (try
          (is (= [{:n (* n 200000)}] (store/query s "SELECT count(*) AS n FROM sig_t")) "every queued transaction was committed")
          (finally (store/close! s) (bus/stop! b))))
      (finally
        (when (.isAlive jproc) (.destroyForcibly jproc))
        (log/set-level! :info)
        (fs/delete-tree dir)))))
