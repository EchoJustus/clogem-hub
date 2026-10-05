;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.daemon
  "The daemon: one per OS user. Starts the bus, opens the database (single
   writer, hub schema), builds the runtime and the registry, loads the
   built-in modules (running their migrations first), serves MCP over
   Streamable HTTP on 127.0.0.1 and, in development, an nREPL on a random
   loopback port. Writes daemon.edn (pid, port, version, nrepl port, db)
   under the runtime directory so other doors can find it. Stopping drains
   the writer before the bus goes down. The single-instance lock and the
   systemd unit arrive in S03."
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [clojure.java.io :as io]
            [org.httpkit.server :as hk]
            [clogem.hub.bus :as bus]
            [clogem.hub.config :as config]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.db.store :as store]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.http :as http]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.sdk.manifest :as manifest]))

(def builtin-manifests
  "Classpath resources of the built-in modules, in load order."
  ["clogem/module/system/manifest.edn"])

(defn load-builtins!
  "Register every built-in module. Returns their entries."
  [reg]
  (mapv (fn [r]
          (if-let [url (io/resource r)]
            (registry/register! reg (manifest/read-manifest url))
            (throw (ex-info (str "built-in manifest missing: " r) {:resource r}))))
        builtin-manifests))

(defn daemon-file-path
  "Where this user's daemon records itself."
  ([] (daemon-file-path (config/runtime-dir)))
  ([dir] (str (fs/path dir "daemon.edn"))))

(defn- write-daemon-file! [dir info]
  (fs/create-dirs dir)
  (let [f (daemon-file-path dir)]
    (spit f (pr-str info))
    f))

(defn read-daemon-file
  "The running daemon's record from daemon.edn, or nil when the file is
   missing, unreadable, or names a process that is no longer alive."
  ([] (read-daemon-file (daemon-file-path)))
  ([path]
   (when (fs/exists? path)
     (let [info (config/read-edn-file path)
           pid (:pid info)]
       (when (and (integer? (:port info))
                  (or (nil? pid)
                      (some-> (java.lang.ProcessHandle/of pid) (.map (reify java.util.function.Function (apply [_ h] (.isAlive ^java.lang.ProcessHandle h)))) (.orElse false))))
         info)))))

(declare stop!)

(defn migrate-module!
  "Run a module's migrations (manifest `:db :migrations`) before it starts."
  [st manifest]
  (when-let [resource (get-in manifest [:db :migrations])]
    (store/migrate! st (:module/id manifest) resource)))

(defn start!
  "Start the daemon. Options: :config (default load-config), :nrepl? (default
   false), :runtime-dir (default config/runtime-dir). Returns the system map
   for stop!."
  [{:keys [config nrepl? runtime-dir] :or {nrepl? false}}]
  (let [config (or config (config/load-config))
        dir (or runtime-dir (config/runtime-dir))
        ;; prove the runtime directory is writable before anything is started
        _ (fs/create-dirs dir)
        b (bus/new-bus)
        system (atom {:config config :bus b})]
    (try
      (let [st (store/open! (assoc (:db config) :path (config/db-path config) :bus b))
            _ (swap! system assoc :store st)
            rt (runtime/new-runtime {:config config :bus b :store st})
            reg (registry/new-registry {:runtime rt :bus b :before-start (partial migrate-module! st)})]
        (swap! system assoc :registry reg :runtime rt)
        (runtime/attach-registry! rt reg)
        (load-builtins! reg))
      (let [reg (:registry @system)
            port-ref (atom nil)
            {:keys [host port allowed-origins]} (:http config)
            server (hk/run-server (http/handler {:registry reg :profile :admin
                                                 :port port-ref :allowed-origins allowed-origins})
                                  {:ip host :port port :legacy-return-value? false})
            _ (swap! system assoc :server server)
            actual-port (hk/server-port server)
            _ (reset! port-ref actual-port)
            nrepl-server (when nrepl? (nrepl/start-server! {:host "127.0.0.1" :port 0 :quiet true}))
            _ (swap! system assoc :nrepl nrepl-server)
            nrepl-port (:port nrepl-server)
            info {:pid (.pid (java.lang.ProcessHandle/current))
                  :host host
                  :port actual-port
                  :version config/hub-version
                  :nrepl-port nrepl-port
                  :db (config/db-path config)
                  :started-at (str (java.time.Instant/now))}
            daemon-file (write-daemon-file! dir info)]
        (log/info (assoc info :msg "daemon started" :daemon-file daemon-file
                         :modules (registry/module-ids reg)))
        (swap! system assoc :port actual-port :nrepl-port nrepl-port :daemon-file daemon-file))
      (catch Exception e
        ;; never leave a bound listener or started modules behind
        (stop! @system)
        (throw e)))))

(defn stop!
  "Stop the HTTP listener and the nREPL, stop every module, drain the writer
   and close the database, stop the bus, remove daemon.edn. Tolerates a
   partially started system."
  [{:keys [server nrepl registry store bus daemon-file]}]
  (when server (try (hk/server-stop! server) (catch Exception e (log/warn {:msg "server stop failed" :error (ex-message e)}))))
  (when nrepl (try (nrepl/stop-server! nrepl) (catch Exception e (log/warn {:msg "nrepl stop failed" :error (ex-message e)}))))
  (when registry (registry/stop-all! registry))
  (when store (try (store/close! store) (catch Exception e (log/warn {:msg "store close failed" :error (ex-message e)}))))
  (when bus (bus/stop! bus))
  (when (and daemon-file (fs/exists? daemon-file)) (fs/delete daemon-file))
  (log/info {:msg "daemon stopped"})
  nil)

(defn- stop-once! [system stopped?]
  (when (compare-and-set! stopped? false true)
    (stop! system)))

(defn install-signal-handlers!
  "Stop `system` on SIGTERM or SIGINT and then exit. The stop runs from the
   signal handler, before the JVM's shutdown hooks: Babashka registers one
   per loaded pod that destroys the pod process, so a stop started from a
   shutdown hook would race it and lose the queued transactions. The hook
   installed here is only a fallback for exits that bypass the signals; it
   closes the pod gateway first so nothing tries to respawn a pod while the
   JVM exits. Returns the atom that records whether the stop ran."
  [system]
  (let [stopped? (atom false)
        handler (reify sun.misc.SignalHandler
                  (handle [_ sig]
                    (log/info {:msg "signal received" :signal (.getName ^sun.misc.Signal sig)})
                    (stop-once! system stopped?)
                    (System/exit 0)))]
    (doseq [s ["TERM" "INT"]]
      (try (sun.misc.Signal/handle (sun.misc.Signal. s) handler)
           (catch Exception e
             (log/warn {:msg "signal handler not installed" :signal s :error (ex-message e)}))))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (when-not @stopped? (sqlite/close!)) (stop-once! system stopped?))))
    stopped?))

(defn -main
  "bb dev: run in the foreground with an nREPL until interrupted."
  [& _args]
  (let [system (start! {:nrepl? true})]
    (install-signal-handlers! system)
    @(promise)))
