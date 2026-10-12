;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.daemon-test
  "The daemon as a process: a graceful stop while transactions are queued
   must drain them before the pod goes away. On POSIX the stop is SIGTERM;
   on Windows, where no SIGTERM exists, it is the in-band system_shutdown
   tool. In-process: the tool stops the daemon and only admins see it."
  (:require [babashka.fs :as fs]
            [clogem.hub.test-support :as support]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.hub.auth :as auth]
            [clogem.hub.bus :as bus]
            [clogem.hub.config :as config]
            [clogem.hub.daemon :as daemon]
            [clogem.hub.db.store :as store]
            [clogem.hub.lock :as lock]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.stdio :as stdio]
            [clogem.hub.platform :as platform]))

(def ^:private meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- call-tool
  "POST a modern tools/call through the stdio proxy's forwarder (it mirrors
   the body into the headers the HTTP binding requires); the parsed reply."
  [port token name]
  (json/parse-string (stdio/forward! (str "http://127.0.0.1:" port "/mcp") token
                                     (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                            :params {:name name :arguments {} :_meta meta-2026}}))
                     true))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [] (cond (pred) true
                   (< (System/currentTimeMillis) deadline) (do (Thread/sleep 100) (recur))
                   :else false))))

(deftest a-graceful-stop-drains-the-writer-before-the-pod-dies
  (log/set-level! :error)
  (let [dir (fs/create-temp-dir {:prefix "clogem-stop"})
        err (fs/file (fs/path dir "child.err"))
        n 12
        proc (p/process {:err err :dir (System/getProperty "user.dir")}
                        "bb" "test/fixtures/db/daemon_stop_child.clj" (str dir) (str n))
        ^java.lang.Process jproc (:proc proc)
        reader (io/reader (:out proc))
        ready (future (loop [] (when-let [line (.readLine ^java.io.BufferedReader reader)]
                                 (if (str/starts-with? line "READY ") (str/split line #" ") (recur)))))]
    (try
      (let [[_ port clients-file :as line] (deref ready 90000 :timeout)]
        (is (vector? line) "the child started and queued its transactions")
        (let [t0 (System/nanoTime)]
          (if (platform/windows?)
            (testing "Windows: system_shutdown over HTTP is the graceful stop"
              (let [token (auth/client-token (auth/read-clients clients-file) "admin")
                    reply (call-tool (parse-long port) token "system_shutdown")]
                (is (true? (get-in reply [:result :structuredContent :stopping])) (pr-str reply))))
            (testing "POSIX: SIGTERM is the graceful stop"
              (.destroy jproc)))
          (is (.waitFor jproc 60 java.util.concurrent.TimeUnit/SECONDS) "the child exited")
          (let [ms (quot (- (System/nanoTime) t0) 1000000)]
            (is (= 0 (.exitValue jproc)) "a graceful stop exits 0")
            (is (< ms 25000) (str "shutdown took " ms " ms: the watchdog did not have to fire")))))
      (let [lines (remove str/blank? (str/split-lines (slurp err)))
            parsed (map #(try (edn/read-string %) (catch Exception _ ::not-edn)) lines)
            msgs (set (map :msg (filter map? parsed)))]
        (is (every? map? parsed) (str "stderr carries only EDN log lines, got: " (pr-str (take 3 (remove map? parsed)))))
        (is (not (contains? msgs "restarting the sqlite pod")) "no pod restart during shutdown")
        (is (contains? msgs (if (platform/windows?) "shutdown requested" "signal received")))
        (is (contains? msgs "daemon stopped")))
      (let [b (bus/new-bus)
            s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})]
        (try
          (is (= [{:n (* n 200000)}] (store/query s "SELECT count(*) AS n FROM sig_t")) "every queued transaction was committed")
          (finally (store/close! s) (bus/stop! b))))
      (finally
        (when (.isAlive jproc) (.destroyForcibly jproc))
        (log/set-level! :info)
        (support/delete-tree! dir)))))

(deftest system-shutdown-stops-an-in-process-daemon
  (log/set-level! :error)
  (let [dir (fs/create-temp-dir {:prefix "clogem-shutdown"})
        shared (str (fs/path dir "shared"))
        config (config/load-config {:http {:port 0} :db {:path (str (fs/path dir "clogem.db"))}})
        system (daemon/start! {:config config :runtime-dir (str (fs/path dir "run"))
                               :clients-file (str (fs/path dir "clients.edn")) :shared-dir shared})
        {:keys [port daemon-file store]} system
        clients @(:clients system)
        admin (auth/client-token clients "admin")
        gui (auth/client-token clients "gui")]
    (try
      (testing "the shared directory carries daemon.edn and one token per client"
        (is (= port (:port (edn/read-string (slurp (str (fs/path shared "daemon.edn")))))))
        (is (= admin (slurp (str (fs/path shared "tokens" "admin.token")))))
        (is (= (set (keys (:clients clients))) (set (map #(str/replace (fs/file-name %) #"\.token$" "") (fs/list-dir (fs/path shared "tokens")))))))
      (testing "the tool is invisible to non-admin profiles"
        (is (= -32602 (get-in (call-tool port gui "system_shutdown") [:error :code]))))
      (store/tx! store :hub ["CREATE TABLE IF NOT EXISTS shut_t (id INTEGER PRIMARY KEY, v INTEGER)"])
      (dotimes [_ 4]
        (future (store/tx! store :hub ["INSERT INTO shut_t (v) WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 100000) SELECT x FROM c"])))
      (testing "an admin's system_shutdown replies, then the daemon stops"
        (let [reply (call-tool port admin "system_shutdown")]
          (is (true? (get-in reply [:result :structuredContent :stopping])) (pr-str reply))
          (is (= (.pid (java.lang.ProcessHandle/current)) (get-in reply [:result :structuredContent :pid]))))
        (is (wait-until #(not (fs/exists? daemon-file)) 60000) "daemon.edn removed by the stop")
        (is (not (fs/exists? (fs/path shared "daemon.edn"))) "the shared copy too")
        (is (not (lock/held? (str (fs/path dir "run")))) "the lock was released")
        (is (true? @(:stopped? system)))
        (is (nil? (daemon/stop! system)) "a second stop is a no-op"))
      (let [b (bus/new-bus)
            s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})]
        (try
          (is (= [{:n 400000}] (store/query s "SELECT count(*) AS n FROM shut_t")) "the queued transactions were committed before the stop")
          (finally (store/close! s) (bus/stop! b))))
      (finally
        (daemon/stop! system)
        (log/set-level! :info)
        (support/delete-tree! dir)))))
