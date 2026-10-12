;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.config
  "Daemon configuration and directories (ADR-0006).

   Windows (the product platform):
     config + data:  %LOCALAPPDATA%\\clogem\\       config.edn, modules.edn, clients.edn, clogem.db, artifacts\\
     cache:          %LOCALAPPDATA%\\clogem\\cache\\
     runtime:        %LOCALAPPDATA%\\clogem\\run\\   daemon.edn, daemon.lock, nREPL port, daemon.log
     shared:         the publisher cache folder when packaged (CLOGEM_SHARED_DIR
                     or `:shared-dir`): a copy of daemon.edn and one token file
                     per client for the other packages of the same publisher
   POSIX (development and CI):
     config ~/.config/clogem/, data ~/.local/share/clogem/, cache ~/.cache/clogem/,
     runtime $XDG_RUNTIME_DIR/clogem/ (or a per-user temp directory)

   Overrides on either platform: CLOGEM_CONFIG_DIR, CLOGEM_SHARED_DIR, the
   XDG_* variables (what the Linux test runs use), CLOGEM_PORT. config.edn is
   read with clojure.edn (no eval). The HTTP listener is validated to be a
   loopback address (PD-9). The database lives at `:db :path` (default
   <data-dir>/clogem.db); the store refuses network, removable and non-NTFS
   volumes (PD-10, clogem.hub.platform)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clogem.hub.platform :as platform]))

(def hub-version "0.1.0")

(def defaults
  {:http {:host "127.0.0.1"
          :port 7788
          ;; Origins allowed on /mcp besides none. Loopback origins are
          ;; always accepted.
          :allowed-origins #{}}
   :bus {:request-timeout-ms 5000}      ; budget of clogem.api/request!
   :db {:path nil                       ; nil → <data-dir>/clogem.db
        :busy-timeout-ms 5000           ; SQLite busy handler per transaction
        :queue 256                      ; bounded writer queue
        :call-timeout-ms 30000          ; watchdog per pod call
        :tx-timeout-ms 60000}           ; wait for queue room plus reply
   :auth {:clients-file nil}            ; nil → <config-dir>/clients.edn (owner-only)
   :shared-dir nil                      ; publisher cache folder (packaged hub), or nil
   :profiles {:llm-small {:max-tools 10}} ; a small model sees at most this many tools
   :audit {:queue 256}                  ; bounded audit queue (one writer thread)
   :subscriptions {:keepalive-ms 15000  ; SSE comment line on quiet listen streams
                   :buffer 64}          ; per-stream notification buffer (dropping)
   :stdio {:client "stdio"              ; clients.edn entry the stdio proxy speaks as
           :autostart? true             ; start the daemon when none is reachable
           :start-timeout-ms 15000}     ; how long the proxy waits for daemon.edn
   :modules {}})

(def loopback-hosts #{"127.0.0.1" "::1" "localhost"})

(defn home [] (System/getProperty "user.home"))

(defn paths
  "The hub directories for an environment, pure: {:config :data :cache
   :runtime :shared} from {:env {\"VAR\" \"value\"} :windows? bool :home
   :tmpdir :user}. Explicit variables win on either platform; the platform
   decides the defaults."
  [{:keys [env windows? home tmpdir user]}]
  (let [e (fn [k] (not-empty (get env k)))
        base (str (fs/path (platform/local-app-data env home) "clogem"))
        xdg (fn [var] (some-> (e var) (fs/path "clogem") str))]
    {:config (or (e "CLOGEM_CONFIG_DIR") (xdg "XDG_CONFIG_HOME")
                 (if windows? base (str (fs/path home ".config" "clogem"))))
     :data (or (xdg "XDG_DATA_HOME")
               (if windows? base (str (fs/path home ".local" "share" "clogem"))))
     :cache (or (xdg "XDG_CACHE_HOME")
                (if windows? (str (fs/path base "cache")) (str (fs/path home ".cache" "clogem"))))
     :runtime (or (xdg "XDG_RUNTIME_DIR")
                  (if windows?
                    (str (fs/path base "run"))
                    (str (fs/path tmpdir (str "clogem-" user) "clogem"))))
     :shared (e "CLOGEM_SHARED_DIR")}))

(defn current-paths
  "`paths` for this process."
  []
  (paths {:env (System/getenv) :windows? (platform/windows?) :home (home)
          :tmpdir (System/getProperty "java.io.tmpdir") :user (System/getProperty "user.name")}))

(defn config-dir [] (:config (current-paths)))

(defn data-dir [] (:data (current-paths)))

(defn cache-dir [] (:cache (current-paths)))

(defn runtime-dir
  "Where daemon.edn, daemon.lock and daemon.log live (see `paths`)."
  []
  (:runtime (current-paths)))

(defn config-file [] (str (fs/path (config-dir) "config.edn")))

(defn db-path
  "The database file: `:db :path` or <data-dir>/clogem.db."
  [config]
  (or (get-in config [:db :path]) (str (fs/path (data-dir) "clogem.db"))))

(defn clients-file
  "The per-client token file: `:auth :clients-file` or <config-dir>/clients.edn."
  [config]
  (or (get-in config [:auth :clients-file]) (str (fs/path (config-dir) "clients.edn"))))

(defn shared-dir
  "The publisher cache folder other packages read, or nil: `:shared-dir`
   from the configuration, else CLOGEM_SHARED_DIR."
  [config]
  (or (:shared-dir config) (:shared (current-paths))))

(defn read-edn-file
  "The EDN map in `path`, or nil when the file is absent or not a map."
  [path]
  (when (fs/exists? path)
    (let [v (edn/read-string {:eof nil} (slurp (str path)))]
      (when (map? v) v))))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    (if (nil? b) a b)))

(defn- parse-port
  "A port from an environment string; throws on anything that is not 0..65535
   so a typo never falls back silently to the default port."
  [s]
  (let [n (try (Long/parseLong s) (catch Exception _ nil))]
    (if (and n (<= 0 n 65535))
      n
      (throw (ex-info (str "CLOGEM_PORT must be 0..65535, got " (pr-str s)) {:value s})))))

(defn validate
  "Throw ex-info when the configuration violates a directive."
  [config]
  (let [{:keys [host port]} (:http config)]
    (when-not (contains? loopback-hosts host)
      (throw (ex-info (str "http host must be a loopback address, got " (pr-str host))
                      {:allowed loopback-hosts})))
    (when-not (and (integer? port) (<= 0 port 65535))
      (throw (ex-info (str "http port must be 0..65535, got " (pr-str port)) {})))
    (let [path (get-in config [:db :path])]
      (when-not (or (nil? path) (and (string? path) (not (str/blank? path))))
        (throw (ex-info (str "db path must be a non-blank string or nil, got " (pr-str path)) {}))))
    (doseq [[k v] (dissoc (:db config) :path)]
      (when-not (and (integer? v) (pos? v))
        (throw (ex-info (str "db " (name k) " must be a positive integer, got " (pr-str v)) {}))))
    (doseq [[k v] (:bus config)]
      (when-not (and (integer? v) (pos? v))
        (throw (ex-info (str "bus " (name k) " must be a positive integer, got " (pr-str v)) {}))))
    (let [f (get-in config [:auth :clients-file])]
      (when-not (or (nil? f) (and (string? f) (not (str/blank? f))))
        (throw (ex-info (str "auth clients-file must be a non-blank string or nil, got " (pr-str f)) {}))))
    (let [d (:shared-dir config)]
      (when-not (or (nil? d) (and (string? d) (not (str/blank? d))))
        (throw (ex-info (str "shared-dir must be a non-blank string or nil, got " (pr-str d)) {}))))
    (doseq [[profile {:keys [max-tools]}] (:profiles config)]
      (when-not (and (integer? max-tools) (pos? max-tools))
        (throw (ex-info (str "profiles " (name profile) " max-tools must be a positive integer, got " (pr-str max-tools)) {}))))
    (doseq [[section keys*] [[:audit [:queue]] [:subscriptions [:keepalive-ms :buffer]] [:stdio [:start-timeout-ms]]]
            k keys*
            :when (contains? (get config section) k)]
      (let [v (get-in config [section k])]
        (when-not (and (integer? v) (pos? v))
          (throw (ex-info (str (name section) " " (name k) " must be a positive integer, got " (pr-str v)) {})))))
    (when (contains? (:stdio config) :client)
      (let [c (get-in config [:stdio :client])]
        (when-not (and (string? c) (not (str/blank? c)))
          (throw (ex-info (str "stdio client must be a non-blank string, got " (pr-str c)) {})))))
    (when (contains? (:stdio config) :autostart?)
      (when-not (boolean? (get-in config [:stdio :autostart?]))
        (throw (ex-info (str "stdio autostart? must be a boolean, got " (pr-str (get-in config [:stdio :autostart?]))) {}))))
    config))

(defn load-config
  "defaults ⊕ config.edn ⊕ overrides ⊕ environment, validated.
   `overrides` is a partial config map (tests pass {:http {:port 0}})."
  ([] (load-config {}))
  ([overrides]
   (let [from-file (read-edn-file (config-file))
         from-env (when-let [p (some-> (not-empty (System/getenv "CLOGEM_PORT")) parse-port)] {:http {:port p}})]
     (validate (reduce deep-merge defaults [from-file overrides from-env])))))
