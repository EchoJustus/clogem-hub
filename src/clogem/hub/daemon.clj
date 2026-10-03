;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.daemon
  "The daemon: one per OS user. Builds the runtime and the registry, loads the
   built-in modules, serves MCP over Streamable HTTP on 127.0.0.1 and, in
   development, an nREPL on a random loopback port. Writes daemon.edn (pid,
   port, version, nrepl port) under the runtime directory so other doors can
   find it. The single-instance lock and systemd unit arrive in S03."
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [clojure.java.io :as io]
            [org.httpkit.server :as hk]
            [clogem.hub.config :as config]
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

(defn- write-daemon-file! [dir info]
  (fs/create-dirs dir)
  (let [f (fs/path dir "daemon.edn")]
    (spit (str f) (pr-str info))
    (str f)))

(defn start!
  "Start the daemon. Options: :config (default load-config), :nrepl? (default
   false), :runtime-dir (default config/runtime-dir). Returns the system map
   for stop!."
  [{:keys [config nrepl? runtime-dir] :or {nrepl? false}}]
  (let [config (or config (config/load-config))
        rt (runtime/new-runtime config)
        reg (registry/new-registry {:runtime rt
                                    :on-change (fn [e] (log/debug {:msg "registry changed" :change (:change e) :module (:module e)}))})
        _ (runtime/attach-registry! rt reg)
        _ (load-builtins! reg)
        port-ref (atom nil)
        {:keys [host port allowed-origins]} (:http config)
        server (hk/run-server (http/handler {:registry reg :profile :admin
                                             :port port-ref :allowed-origins allowed-origins})
                              {:ip host :port port :legacy-return-value? false})
        actual-port (hk/server-port server)
        _ (reset! port-ref actual-port)
        nrepl-server (when nrepl? (nrepl/start-server! {:host "127.0.0.1" :port 0 :quiet true}))
        nrepl-port (:port nrepl-server)
        dir (or runtime-dir (config/runtime-dir))
        info {:pid (.pid (java.lang.ProcessHandle/current))
              :host host
              :port actual-port
              :version config/hub-version
              :nrepl-port nrepl-port
              :started-at (str (java.time.Instant/now))}
        daemon-file (write-daemon-file! dir info)]
    (log/info (assoc info :msg "daemon started" :daemon-file daemon-file
                     :modules (registry/module-ids reg)))
    {:config config :registry reg :runtime rt :server server
     :nrepl nrepl-server :port actual-port :nrepl-port nrepl-port :daemon-file daemon-file}))

(defn stop!
  "Stop the HTTP listener and the nREPL, stop every module, remove daemon.edn."
  [{:keys [server nrepl registry daemon-file]}]
  (when server (hk/server-stop! server))
  (when nrepl (nrepl/stop-server! nrepl))
  (when registry (registry/stop-all! registry))
  (when (and daemon-file (fs/exists? daemon-file)) (fs/delete daemon-file))
  (log/info {:msg "daemon stopped"})
  nil)

(defn -main
  "bb dev: run in the foreground with an nREPL until interrupted."
  [& _args]
  (let [system (start! {:nrepl? true})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (stop! system))))
    @(promise)))
