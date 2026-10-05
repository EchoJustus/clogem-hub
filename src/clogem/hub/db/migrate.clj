;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.migrate
  "Schema migrations per module, tracked in schema_migrations(module,
   version). A module's manifest names a classpath directory
   (`:db {:migrations \"clogem/module/<id>/migrations\"}`) of files
   `NNNN-slug.sql`; each file is applied once, as one writer transaction
   together with its schema_migrations row, in version order. Objects a
   module creates must be prefixed `<id>_` (dashes become underscores, as
   in tool names); the hub's own migrations (module :hub) are exempt.

   WAL mode is set here, once: it is the one persistent pragma."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.hub.db.sql :as sql]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.log :as log]))

(def schema-table-sql
  "CREATE TABLE IF NOT EXISTS schema_migrations (
     module TEXT NOT NULL,
     version INTEGER NOT NULL,
     name TEXT NOT NULL,
     applied_at INTEGER NOT NULL,
     PRIMARY KEY (module, version))")

(defn ensure-wal!
  "Switch the database file to WAL (persistent) and verify it took."
  [db]
  (sqlite/execute! db "PRAGMA journal_mode=WAL")
  (let [mode (-> (sqlite/query db "PRAGMA journal_mode") first :journal_mode str str/lower-case)]
    (when-not (= "wal" mode)
      (throw (ex-info (str "could not enable WAL, journal_mode is " mode) {:type :wal-unavailable :db db :mode mode})))
    mode))

(defn ensure-schema-table! [db]
  (sqlite/execute! db schema-table-sql))

(def file-re #"^(\d{1,9})[-_][^/]*\.sql$")

(defn- resource-dir
  "The directory `resource` names: an absolute filesystem directory (modules
   loaded from modules.edn) or a classpath directory, as a path."
  [resource]
  (let [url (when-not (and (fs/absolute? resource) (fs/directory? resource)) (io/resource resource))]
    (cond
      (and (fs/absolute? resource) (fs/directory? resource))
      (fs/path resource)

      (nil? url)
      (throw (ex-info (str "migrations resource not found on the classpath: " resource)
                      {:type :migrations-missing :resource resource}))

      (not= "file" (.getProtocol ^java.net.URL url))
      (throw (ex-info (str "migrations must live in a directory, not an archive: " resource)
                      {:type :migrations-unlistable :resource resource}))

      :else
      (let [dir (fs/path (.toURI ^java.net.URL url))]
        (when-not (fs/directory? dir)
          (throw (ex-info (str "migrations resource is not a directory: " resource)
                          {:type :migrations-not-directory :resource resource})))
        dir))))

(defn load-migrations
  "[{:version n :name file :sql text} …] sorted by version, from the
   classpath directory `resource`. Throws on duplicate versions."
  [resource]
  (let [files (->> (fs/list-dir (resource-dir resource))
                   (keep (fn [p]
                           (when-let [[_ v] (re-matches file-re (fs/file-name p))]
                             {:version (Long/parseLong v) :name (fs/file-name p) :sql (slurp (str p))})))
                   (sort-by :version)
                   vec)
        dups (->> files (map :version) frequencies (filter (fn [[_ n]] (> n 1))) (map first))]
    (when (seq dups)
      (throw (ex-info (str "duplicate migration versions: " (str/join ", " dups))
                      {:type :migrations-duplicate :resource resource :versions dups})))
    files))

(def create-re
  #"(?i)\bCREATE\s+(?:TEMP(?:ORARY)?\s+)?(?:VIRTUAL\s+)?(?:UNIQUE\s+)?(?:TABLE|INDEX|TRIGGER|VIEW)\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"`\[]?([A-Za-z_][A-Za-z0-9_]*)")

(defn created-names
  "Names of the tables, indexes, triggers and views `sql` creates."
  [sql]
  (mapv second (re-seq create-re sql)))

(defn table-prefix
  "The prefix a module's database objects carry: `<id>_`, dashes as underscores."
  [module]
  (str (str/replace (name module) "-" "_") "_"))

(defn prefix-problems
  "Objects in `sql` that do not carry the module's prefix (none for :hub)."
  [module sql]
  (if (= :hub module)
    []
    (let [prefix (table-prefix module)]
      (vec (for [n (created-names sql) :when (not (str/starts-with? n prefix))]
             (str "object " n " must be prefixed " prefix))))))

(defn applied-versions
  "The set of versions already applied for `module`."
  [db module]
  (set (map :version (sqlite/query db ["SELECT version FROM schema_migrations WHERE module = ?" (name module)]))))

(defn- check! [module {:keys [version name sql]}]
  (let [problems (concat (prefix-problems module sql) (sql/statement-problems sql true))]
    (when (seq problems)
      (throw (ex-info (str "migration " name " of " module " rejected: " (first problems))
                      {:type :migration-rejected :module module :version version :problems (vec problems)})))))

(defn migrate!
  "Apply the pending migrations of `module` found in the classpath
   directory `resource`, each through `submit` (fn [tx] reply) as one
   transaction with its schema_migrations row. Returns
   {:module :applied [versions] :current n}. Throws {:type :migration-failed}
   on the first failure, leaving earlier migrations applied."
  [{:keys [db submit]} module resource]
  (let [migrations (load-migrations resource)
        done (applied-versions db module)
        pending (remove #(contains? done (:version %)) migrations)]
    (doseq [m pending] (check! module m))
    (doseq [{:keys [version name sql]} pending]
      (let [reply (submit {:module module
                           :migration? true
                           :statements [sql
                                        ["INSERT INTO schema_migrations (module, version, name, applied_at) VALUES (?, ?, ?, ?)"
                                         (clojure.core/name module) version name (System/currentTimeMillis)]]})]
        (if (:ok reply)
          (log/info {:msg "migration applied" :module module :version version :name name})
          (throw (ex-info (str "migration " name " of " module " failed: " (get-in reply [:error :message]))
                          {:type :migration-failed :module module :version version :error (:error reply)})))))
    {:module module
     :applied (mapv :version pending)
     :current (or (:version (last migrations)) 0)}))
