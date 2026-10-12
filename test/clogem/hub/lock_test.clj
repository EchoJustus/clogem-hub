;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.lock-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.hub.lock :as lock]))

(deftest one-holder-per-directory
  (let [dir (str (fs/create-temp-dir {:prefix "clogem-lock"}))]
    (try
      (is (not (lock/held? dir)) "nothing holds a fresh directory")
      (let [h (lock/acquire! dir)]
        (is (fs/exists? (lock/lock-path dir)))
        (is (lock/held? dir))
        (is (= (.pid (java.lang.ProcessHandle/current)) (lock/holder-pid (lock/lock-path dir))) "the holder wrote its pid")
        (testing "a second acquire in the same process is refused with the holder"
          (let [e (try (lock/acquire! dir) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :already-running (:type (ex-data e))))
            (is (= (.pid (java.lang.ProcessHandle/current)) (:holder (ex-data e))))))
        (testing "another process sees the lock as held"
          (let [{:keys [out]} (p/shell {:out :string :err :string :dir (System/getProperty "user.dir")}
                                       "bb" "-e" (str "(require 'clogem.hub.lock) (println (clogem.hub.lock/held? " (pr-str dir) "))"))]
            (is (= "true" (str/trim out)))))
        (lock/release! h)
        (is (not (lock/held? dir)) "released by closing the channel")
        (is (nil? (lock/release! h)) "release is idempotent")
        (is (nil? (lock/release! nil)))
        (let [h2 (lock/acquire! dir)]
          (is (lock/held? dir) "re-acquirable after release")
          (lock/release! h2)))
      (finally (fs/delete-tree dir)))))

(deftest the-holder-pid-falls-back-to-daemon-edn
  ;; on Windows the locked bytes cannot be read by another process; the
  ;; record next to the lock answers instead
  (let [dir (str (fs/create-temp-dir {:prefix "clogem-lock-pid"}))]
    (try
      (is (nil? (lock/holder-pid (lock/lock-path dir))) "no lock, no record")
      (spit (str (fs/path dir "daemon.edn")) (pr-str {:pid 4242 :port 1}))
      (is (= 4242 (lock/holder-pid (lock/lock-path dir))) "an unreadable or missing lock file defers to daemon.edn")
      (spit (lock/lock-path dir) "77\n")
      (is (= 77 (lock/holder-pid (lock/lock-path dir))) "a readable lock file wins")
      (spit (str (fs/path dir "daemon.edn")) "[not a map]")
      (fs/delete (lock/lock-path dir))
      (is (nil? (lock/holder-pid (lock/lock-path dir))) "a bad record is ignored")
      (finally (fs/delete-tree dir)))))

(deftest the-lock-dies-with-its-process
  (let [dir (str (fs/create-temp-dir {:prefix "clogem-lock-child"}))]
    (try
      (let [proc (p/process {:out :string :err :string :dir (System/getProperty "user.dir")}
                            "bb" "-e" (str "(require 'clogem.hub.lock) (clogem.hub.lock/acquire! " (pr-str dir) ") (println \"HELD\") (flush) (Thread/sleep 60000)"))]
        (try
          (let [deadline (+ (System/currentTimeMillis) 30000)]
            (loop [] (when (and (not (lock/held? dir)) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50) (recur))))
          (is (lock/held? dir) "the child holds the lock")
          (finally (.destroyForcibly ^java.lang.Process (:proc proc))
                   (.waitFor ^java.lang.Process (:proc proc) 10 java.util.concurrent.TimeUnit/SECONDS))))
      (is (not (lock/held? dir)) "the kernel released it when the holder died")
      (finally (fs/delete-tree dir)))))
