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
                               :clients-file (str (fs/path dir "clients.edn")) :shared-dir shared
                               :modules-file (str (fs/path dir "modules.edn"))})
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

(defn- write-external-module!
  "A minimal module under `root` (src/ and resources/ as modules.edn expects)
   with one read-only tool; what a module from another repository looks
   like on disk."
  [root]
  (let [src (fs/path root "src" "clogem" "module" "exttest")
        res (fs/path root "resources" "clogem" "module" "exttest")]
    (fs/create-dirs src)
    (fs/create-dirs res)
    (spit (str (fs/path src "core.clj"))
          (str "(ns clogem.module.exttest.core (:require [clogem.api :as api]))\n"
               "(def module {:start (fn [ctx] (api/log ctx :info {:msg \"exttest started\"}) {})"
               " :stop (fn [_] nil) :health (fn [_] {:status :ok})})\n"
               "(defn ping [_ctx _args] {:pong true})\n"))
    (spit (str (fs/path res "manifest.edn"))
          (pr-str {:manifest/version 1 :module/id :exttest :module/version "0.0.1" :module/license :epl-2.0
                   :module/description "An external test module."
                   :module/entry 'clogem.module.exttest.core/module
                   :module/requires {:clogem/api "1" :bins [] :env #{}}
                   :bus {:publishes {} :subscribes #{}}
                   :db {:migrations nil}
                   :mcp {:tools [{:name "exttest_ping" :title "Ping"
                                  :description "Reply pong. Use it to check that an external module is served."
                                  :handler 'clogem.module.exttest.core/ping
                                  :input [:map {:closed true}] :output [:map [:pong :boolean]]
                                  :annotations {:readOnlyHint true :idempotentHint true} :meta {}
                                  :profiles #{:gui :llm-small :llm-large :admin}}]
                         :resources [] :resource-templates [] :prompts []}
                   :ui {:card {:title "Ext" :icon "puzzle" :quick-actions []} :routes []}}))
    root))

(deftest external-modules-load-from-modules-edn
  (log/set-level! :error)
  (let [dir (fs/create-temp-dir {:prefix "clogem-ext"})
        _ (write-external-module! (fs/path dir "extmod"))
        modules-file (str (fs/path dir "modules.edn"))
        _ (spit modules-file (pr-str {:modules [{:id :exttest :root "extmod"}
                                                {:id :ghost :root "missing" :enabled? true}
                                                {:id :off :root "nowhere" :enabled? false}]}))
        config (config/load-config {:http {:port 0} :db {:path (str (fs/path dir "clogem.db"))}})
        system (daemon/start! {:config config :runtime-dir (str (fs/path dir "run"))
                               :clients-file (str (fs/path dir "clients.edn")) :modules-file modules-file})
        {:keys [port external-modules]} system
        clients @(:clients system)
        admin (auth/client-token clients "admin")
        llm (auth/client-token clients "llm")]
    (try
      (testing "the loader's report"
        (is (= [:exttest] (:loaded external-modules)))
        (is (= [:ghost] (mapv :id (:failed external-modules))) "a missing root fails that entry only"))
      (testing "the built-in modules and the external one are listed"
        (let [reply (call-tool port admin "system_list_modules")
              modules (get-in reply [:result :structuredContent :modules])]
          (is (= #{"exttest" "media" "system" "ui"} (set (map :id modules))) (pr-str reply))
          (is (every? #(= "ready" (:status %)) (remove (comp #{"media"} :id) modules)) "system, ui and the external module are ready")))
      (testing "the external module's tool is served to any profile it names"
        (is (true? (get-in (call-tool port llm "exttest_ping") [:result :structuredContent :pong]))))
      (finally
        (daemon/stop! system)
        (log/set-level! :info)
        (support/delete-tree! dir)))))
