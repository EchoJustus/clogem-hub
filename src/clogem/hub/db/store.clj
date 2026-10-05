;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.store
  "The daemon's database: one SQLite file, WAL, one writer, read-only reads.

   open! checks the path (PD-10: never under /mnt or ~/RUN, never on a
   9p/v9fs/drvfs filesystem), creates the directory, sets WAL once, creates
   schema_migrations, starts the writer as owner of :db/tx on the bus and
   applies the hub's own migrations (the jobs table). Modules get tx!,
   query and migrate!; close! drains the writer and checkpoints the WAL."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clogem.hub.db.migrate :as migrate]
            [clogem.hub.db.reader :as reader]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.db.writer :as writer]
            [clogem.hub.log :as log]))

(def defaults writer/defaults)

(def hub-migrations "clogem/hub/db/migrations")

(def unsafe-filesystems #{"9p" "v9fs" "drvfs"})

(defn- nearest-existing [path]
  (loop [p (fs/absolutize path)]
    (if (or (nil? p) (fs/exists? p)) p (recur (fs/parent p)))))

(defn filesystem-type
  "The filesystem type of `path` (or its nearest existing ancestor) as
   `findmnt -n -o FSTYPE -T` reports it; nil when findmnt is unavailable."
  [path]
  (try
    (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                      "findmnt" "-n" "-o" "FSTYPE" "-T" (str (nearest-existing path)))]
      (when (zero? exit) (not-empty (str/trim out))))
    (catch Exception _ nil)))

(defn check-path!
  "Throw unless `path` may hold the database (PD-10). Returns the
   filesystem type, or nil when it could not be determined."
  [path]
  (let [abs (str (fs/absolutize path))
        run (str (fs/path (System/getProperty "user.home") "RUN"))]
    (when (or (str/starts-with? abs "/mnt/") (= abs run) (str/starts-with? abs (str run "/")))
      (throw (ex-info (str "refusing a database path under a Windows mount: " abs) {:type :unsafe-path :path abs})))
    (let [fstype (filesystem-type abs)]
      (when (contains? unsafe-filesystems fstype)
        (throw (ex-info (str "refusing a database path on a " fstype " filesystem: " abs)
                        {:type :unsafe-filesystem :path abs :fstype fstype})))
      (when (nil? fstype)
        (log/warn {:msg "filesystem type unknown (findmnt unavailable), continuing" :path abs}))
      fstype)))

(defn open!
  "Open (creating if needed) the database at :path. Needs :bus. Options as
   clogem.hub.db.writer/defaults. Returns the store."
  [{:keys [path bus] :as opts}]
  (when-not (string? path) (throw (ex-info "store needs a :path" {:type :no-path})))
  (when-not bus (throw (ex-info "store needs a :bus" {:type :no-bus})))
  (let [opts (merge defaults opts)
        fstype (check-path! path)]
    (fs/create-dirs (fs/parent (fs/absolutize path)))
    (migrate/ensure-wal! path)
    (migrate/ensure-schema-table! path)
    (let [w (writer/start! (assoc opts :db path))
          store {:path path :bus bus :writer w :opts opts :fstype fstype}]
      (try
        (let [r (migrate/migrate! {:db path :submit #(writer/submit! w %)} :hub hub-migrations)]
          (log/info {:msg "database open" :path path :fstype fstype :hub-schema (:current r) :applied (:applied r)})
          store)
        (catch Exception e
          (writer/stop! w)
          (throw e))))))

(defn tx!
  "Run `statements` as one transaction on behalf of `module`; the writer's reply."
  [store module statements]
  (writer/submit! (:writer store) {:module module :statements statements}))

(defn query
  "Rows of the read-only query `q`."
  [store q]
  (reader/query (:path store) q (:opts store)))

(defn migrate!
  "Apply `module`'s pending migrations from the classpath directory `resource`."
  [store module resource]
  (migrate/migrate! {:db (:path store) :submit #(writer/submit! (:writer store) %)} module resource))

(defn stats [store]
  {:path (:path store) :fstype (:fstype store) :writer (writer/stats (:writer store))})

(defn close!
  "Drain the writer, then checkpoint and truncate the WAL."
  [{:keys [writer path]}]
  (writer/stop! writer)
  (try (sqlite/execute! path "PRAGMA wal_checkpoint(TRUNCATE)")
       (catch Exception e (log/warn {:msg "wal checkpoint failed" :error (ex-message e)})))
  (log/info {:msg "database closed" :path path})
  nil)
