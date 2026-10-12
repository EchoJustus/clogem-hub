;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.store
  "The daemon's database: one SQLite file, WAL, one writer, read-only reads.

   open! checks the path (PD-10 through clogem.hub.platform: on Windows a
   fixed NTFS or ReFS drive, never a UNC path, a network, removable or FAT
   volume; on POSIX never a 9p/v9fs/drvfs mount or a network filesystem; a
   warning under a cloud-synced folder), creates the directory, sets WAL once, creates
   schema_migrations, starts the writer as owner of :db/tx on the bus and
   applies the hub's own migrations (the jobs table). Modules get tx!,
   query and migrate!; close! drains the writer."
  (:require [babashka.fs :as fs]
            [clogem.hub.db.migrate :as migrate]
            [clogem.hub.db.reader :as reader]
            [clogem.hub.db.writer :as writer]
            [clogem.hub.log :as log]
            [clogem.hub.platform :as platform]))

(def defaults writer/defaults)

(def hub-migrations "clogem/hub/db/migrations")

(defn volume-info
  "clogem.hub.platform/volume-info, a seam for tests."
  [path]
  (platform/volume-info path))

(defn check-path!
  "Throw unless `path` may hold the database (PD-10). Returns the
   filesystem type, or nil when it could not be determined."
  [path]
  (let [{:keys [fstype] :as info} (volume-info path)
        abs (:path info)]
    (when-let [problem (platform/volume-problem info)]
      (throw (ex-info (str "refusing a database path on " problem ": " abs)
                      {:type :unsafe-filesystem :path abs :fstype fstype :volume info})))
    (when (nil? fstype)
      (log/warn {:msg "filesystem type unknown, continuing" :path abs}))
    (when (platform/cloud-placeholder? abs)
      (log/warn {:msg "database path lies under a cloud-synced folder; a sync client can lock or corrupt a live database" :path abs}))
    fstype))

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
  "Drain the writer. No explicit checkpoint: the pod opens a connection per
   call, and SQLite checkpoints and removes the WAL when the last connection
   closes, so every call already leaves the file checkpointed; an explicit
   one would race Babashka's own pod shutdown hook during daemon shutdown."
  [{:keys [writer path]}]
  (writer/stop! writer)
  (log/info {:msg "database closed" :path path})
  nil)
