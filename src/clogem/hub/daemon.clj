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
   loopback port. Proprietary modules listed in modules.edn (the config
   directory, or `:modules-file`) are loaded after the built-in ones; a
   failing entry is logged, never fatal (clogem.hub.modules). Writes daemon.edn (pid, port, version, nrepl port, db,
   lock, clients file) under the runtime directory so other doors can find
   it, and, when a shared directory is configured (the publisher cache
   folder of the packaged hub), a copy of it plus one token file per client
   there for the other packages of the same publisher. One daemon per OS
   user: the single-instance lock (clogem.hub.lock) is taken before anything
   else and a second start refuses. Clients and their profiles come from
   clients.edn (clogem.hub.auth, created on first start), every tools/call is
   audited (clogem.hub.audit) and change notifications flow through the
   subscription feed (clogem.hub.mcp.subscriptions).

   Stopping ends the listen streams gracefully, drains the audit queue and
   the writer, then releases the lock. It is requested in-band by the
   `system_shutdown` tool (bus command :system/shutdown, owned here; the
   process exits once stopped when started by -main), by SIGTERM/SIGINT
   where the OS delivers them, and as a last resort by the JVM shutdown hook
   (ADR-0006: Windows has no SIGTERM; a hard kill runs nothing, which the
   lock and the WAL already cover)."
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [clojure.java.io :as io]
            [org.httpkit.server :as hk]
            [clogem.hub.audit :as audit]
            [clogem.hub.auth :as auth]
            [clogem.hub.bus :as bus]
            [clogem.hub.config :as config]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.db.store :as store]
            [clogem.hub.llm :as llm]
            [clogem.hub.lock :as lock]
            [clogem.hub.log :as log]
            [clogem.hub.media.process :as process]
            [clogem.hub.mcp.http :as http]
            [clogem.hub.mcp.methods :as methods]
            [clogem.hub.mcp.subscriptions :as subs]
            [clogem.hub.modules :as modules]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.sdk.manifest :as manifest]))

(def builtin-manifests
  "Classpath resources of the built-in modules, in load order."
  ["clogem/module/system/manifest.edn"
   "clogem/module/media/manifest.edn"
   "clogem/module/ui/manifest.edn"])

(def shutdown-command
  "The bus command the `system` module sends to stop the daemon."
  :system/shutdown)

(def shutdown-delay-ms
  "How long the stop waits after the shutdown reply, so the reply leaves
   before the listener closes."
  250)

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

(defn- profile-limits
  "`:profiles {p {:max-tools n}}` → {p n} for the registry."
  [config]
  (into {} (for [[p {:keys [max-tools]}] (:profiles config) :when max-tools] [p max-tools])))

(defn- publish-shared!
  "Copy daemon.edn and one owner-only token file per client into the shared
   directory (the publisher cache folder of the packaged hub), when one is
   configured. Returns the shared daemon.edn path or nil."
  [shared info clients]
  (when shared
    (fs/create-dirs shared)
    (let [f (str (fs/path shared "daemon.edn"))]
      (spit f (pr-str info))
      (auth/export-tokens! (str (fs/path shared "tokens")) clients)
      (log/info {:msg "shared directory published" :dir (str shared)})
      f)))

(defn- register-shutdown!
  "Own :system/shutdown on the bus: reply at once, then stop `system` on a
   separate thread after `shutdown-delay-ms` and, when `exit?`, exit 0."
  [b system exit?]
  (bus/register-owner! b shutdown-command
                       (fn [_request]
                         (log/info {:msg "shutdown requested"})
                         (future
                           (Thread/sleep shutdown-delay-ms)
                           (stop! @system)
                           (when exit? (System/exit 0)))
                         {:ok true :result {:stopping true :pid (.pid (java.lang.ProcessHandle/current))}})))

(defn start!
  "Start the daemon. Options: :config (default load-config), :nrepl? (default
   false), :runtime-dir (default config/runtime-dir), :clients-file (default
   config/clients-file), :shared-dir (default config/shared-dir, may be nil),
   :modules-file (default config/modules-file; absent → no external modules),
   :exit-on-stop? (default false; -main passes true so a `system_shutdown`
   ends the process). Returns the system map for stop!. Throws ex-info
   {:type :already-running} when another daemon holds this user's lock."
  [{:keys [config nrepl? runtime-dir clients-file shared-dir modules-file exit-on-stop?]
    :or {nrepl? false exit-on-stop? false}}]
  (let [config (or config (config/load-config))
        dir (or runtime-dir (config/runtime-dir))
        shared (or shared-dir (config/shared-dir config))
        ;; prove the runtime directory is writable, then take the single-instance
        ;; lock before anything else is started
        _ (fs/create-dirs dir)
        lk (lock/acquire! dir)
        system (atom {:config config :lock lk :stopped? (atom false)})]
    (try
      (let [b (bus/new-bus)
            _ (swap! system assoc :bus b)
            st (store/open! (assoc (:db config) :path (config/db-path config) :bus b))
            _ (swap! system assoc :store st)
            rt (runtime/new-runtime {:config config :bus b :store st
                                     :process (process/runner config)
                                     :llm (llm/chat-fn config)})
            reg (registry/new-registry {:runtime rt :bus b :before-start (partial migrate-module! st)
                                        :limits (merge registry/default-limits (profile-limits config))})]
        (swap! system assoc :registry reg :runtime rt)
        (runtime/attach-registry! rt reg)
        (load-builtins! reg)
        (swap! system assoc :external-modules
               (modules/load-all! reg (or modules-file (config/modules-file config))))
        (let [clients-path (or clients-file (config/clients-file config))
              clients (auth/load-clients! clients-path)
              au (audit/start! st (:audit config))
              feed (subs/start! b (:subscriptions config))]
          (swap! system assoc :clients-file (str clients-path) :clients (atom clients) :audit au :subscriptions feed)))
      (let [{:keys [registry clients audit subscriptions bus]} @system
            port-ref (atom nil)
            {:keys [host port allowed-origins]} (:http config)
            server (hk/run-server (http/handler {:registry registry :clients clients :audit audit
                                                 :subscriptions subscriptions
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
                  :lock (:path lk)
                  :clients-file (:clients-file @system)
                  :shared-dir (some-> shared str)
                  :started-at (str (java.time.Instant/now))}
            daemon-file (write-daemon-file! dir info)
            shared-file (publish-shared! shared info @clients)]
        (register-shutdown! bus system exit-on-stop?)
        (log/info (assoc info :msg "daemon started" :daemon-file daemon-file
                         :modules (registry/module-ids registry)
                         :bus (boolean bus)))
        (swap! system assoc :port actual-port :nrepl-port nrepl-port :daemon-file daemon-file
               :shared-file shared-file))
      (catch Exception e
        ;; never leave a bound listener, started modules or the lock behind
        (stop! @system)
        (throw e)))))

(defn stop!
  "End the listen streams gracefully, stop the HTTP listener and the nREPL,
   stop every module, drain the audit queue and the writer, close the
   database, stop the bus, remove daemon.edn (and its shared copy) and
   release the lock. Tolerates a partially started system; runs once per
   system (the `:stopped?` flag), later calls are no-ops."
  [{:keys [server nrepl registry store bus daemon-file shared-file lock subscriptions audit stopped?] :as system}]
  (when (and system (or (nil? stopped?) (compare-and-set! stopped? false true)))
    (when subscriptions (try (subs/stop! subscriptions methods/server-info)
                             (catch Exception e (log/warn {:msg "subscriptions stop failed" :error (ex-message e)}))))
    (when server (try (hk/server-stop! server) (catch Exception e (log/warn {:msg "server stop failed" :error (ex-message e)}))))
    (when nrepl (try (nrepl/stop-server! nrepl) (catch Exception e (log/warn {:msg "nrepl stop failed" :error (ex-message e)}))))
    (when registry (registry/stop-all! registry))
    (when audit (try (audit/stop! audit) (catch Exception e (log/warn {:msg "audit stop failed" :error (ex-message e)}))))
    (when store (try (store/close! store) (catch Exception e (log/warn {:msg "store close failed" :error (ex-message e)}))))
    (when bus (bus/stop! bus))
    (doseq [f [daemon-file shared-file]]
      (when (and f (fs/exists? f)) (try (fs/delete f) (catch Exception e (log/warn {:msg "daemon file not removed" :path f :error (ex-message e)})))))
    (lock/release! lock)
    (log/info {:msg "daemon stopped"}))
  nil)

(defn install-signal-handlers!
  "Stop `system` on SIGTERM or SIGINT where the OS delivers them and then
   exit. The stop runs from the signal handler, before the JVM's shutdown
   hooks: Babashka registers one per loaded pod that destroys the pod
   process, so a stop started from a shutdown hook would race it and lose
   the queued transactions. The hook installed here is only a fallback for
   exits that bypass the signals; it closes the pod gateway first so nothing
   tries to respawn a pod while the JVM exits. On Windows SIGTERM does not
   exist (a hard kill runs nothing); the in-band `system_shutdown` is the
   graceful stop there. Returns the system's `:stopped?` flag."
  [system]
  (let [stopped? (or (:stopped? system) (atom false))
        system (assoc system :stopped? stopped?)
        handler (reify sun.misc.SignalHandler
                  (handle [_ sig]
                    (log/info {:msg "signal received" :signal (.getName ^sun.misc.Signal sig)})
                    (stop! system)
                    (System/exit 0)))]
    (doseq [s ["TERM" "INT"]]
      (try (sun.misc.Signal/handle (sun.misc.Signal. s) handler)
           (catch Exception e
             (log/warn {:msg "signal handler not installed" :signal s :error (ex-message e)}))))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (when-not @stopped? (sqlite/close!)) (stop! system))))
    stopped?))

(defn -main
  "Run in the foreground until stopped. `--nrepl` (bb dev) adds the
   development nREPL; `bb daemon` (the stdio proxy's autostart and the
   package's startup task) runs without it. A second daemon exits 3 with one
   line on stderr; `system_shutdown` exits 0 once the stop has drained."
  [& args]
  (let [system (try (start! {:nrepl? (boolean (some #{"--nrepl"} args)) :exit-on-stop? true})
                    (catch clojure.lang.ExceptionInfo e
                      (if (= :already-running (:type (ex-data e)))
                        (do (log/error {:msg "refusing to start: another daemon is running"
                                        :lock (:lock (ex-data e)) :holder (:holder (ex-data e))})
                            (System/exit 3))
                        (throw e))))]
    (install-signal-handlers! system)
    @(promise)))
