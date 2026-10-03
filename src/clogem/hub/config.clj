;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.config
  "Daemon configuration and XDG paths.

   config:  ~/.config/clogem/      config.edn, modules.edn, clients.edn
   data:    ~/.local/share/clogem/ clogem.db, artifacts/
   cache:   ~/.cache/clogem/
   runtime: $XDG_RUNTIME_DIR/clogem/  daemon.edn, lock, nREPL port

   config.edn is read with clojure.edn (no eval). Environment overrides:
   CLOGEM_CONFIG_DIR, CLOGEM_PORT. The HTTP listener is validated to be a
   loopback address (PD-9): anything else is rejected."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def hub-version "0.1.0")

(def defaults
  {:http {:host "127.0.0.1"
          :port 7788
          ;; Origins allowed on /mcp besides none. Loopback origins are
          ;; always accepted.
          :allowed-origins #{}}
   :modules {}})

(def loopback-hosts #{"127.0.0.1" "::1" "localhost"})

(defn- env [k] (not-empty (System/getenv k)))

(defn home [] (System/getProperty "user.home"))

(defn- xdg [var default-rel]
  (or (env var) (str (fs/path (home) default-rel))))

(defn config-dir []
  (or (env "CLOGEM_CONFIG_DIR") (str (fs/path (xdg "XDG_CONFIG_HOME" ".config") "clogem"))))

(defn data-dir [] (str (fs/path (xdg "XDG_DATA_HOME" ".local/share") "clogem")))

(defn cache-dir [] (str (fs/path (xdg "XDG_CACHE_HOME" ".cache") "clogem")))

(defn runtime-dir
  "$XDG_RUNTIME_DIR/clogem, or a per-user directory under the system temp
   directory when XDG_RUNTIME_DIR is unset (containers, CI)."
  []
  (let [base (or (env "XDG_RUNTIME_DIR")
                 (str (fs/path (System/getProperty "java.io.tmpdir")
                               (str "clogem-" (System/getProperty "user.name")))))]
    (str (fs/path base "clogem"))))

(defn config-file [] (str (fs/path (config-dir) "config.edn")))

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
    config))

(defn load-config
  "defaults ⊕ config.edn ⊕ overrides ⊕ environment, validated.
   `overrides` is a partial config map (tests pass {:http {:port 0}})."
  ([] (load-config {}))
  ([overrides]
   (let [from-file (read-edn-file (config-file))
         from-env (when-let [p (some-> (env "CLOGEM_PORT") parse-port)] {:http {:port p}})]
     (validate (reduce deep-merge defaults [from-file overrides from-env])))))
