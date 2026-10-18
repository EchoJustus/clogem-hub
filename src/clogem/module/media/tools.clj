;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.tools
  "Tool handlers of the built-in `media` module, media_register and
   media_probe, plus the row helpers its resource handler shares.

   The library is the table media_items (migration 0001): one row per
   content, keyed by the media id clogem.module.media.fingerprint derives
   from the file's size, first and last MiB and duration. `path` is UNIQUE
   and nullable: it is where that content was last registered. When other
   content is registered at a path a row holds, that row loses its path
   (NULL) and the new content gets its own row; when known content turns up
   at another path, its row is relinked there.

   Everything reaches the hub through clogem.api: rows are read with
   `query` (one SELECT with parameters), written with `submit-tx!` (a
   vector of [sql & params] statements the single writer runs as one
   transaction), files are probed through `run-process!`
   (clogem.module.media.probe) and changes are announced with `publish!`
   (:media/registered, and :resource/updated for every
   clogem://media/item/{id} whose row changed). User files are only read
   (PD-6). The results match the output schemas in manifest.edn, which the
   hub validates."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clogem.api :as api]
            [clogem.module.media.fingerprint :as fingerprint]
            [clogem.module.media.probe :as probe]))

;; ---------------------------------------------------------------------------
;; SQL

(def columns "id, path, size, fingerprint, duration_ms, probe, created_ms, updated_ms")

(def select-by-id (str "SELECT " columns " FROM media_items WHERE id = ?"))

(def select-by-path (str "SELECT " columns " FROM media_items WHERE path = ?"))

(def insert-item
  "Parameters: id path size fingerprint duration_ms probe created_ms updated_ms."
  "INSERT INTO media_items (id, path, size, fingerprint, duration_ms, probe, created_ms, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")

(def relink-item
  "Parameters: path updated_ms id."
  "UPDATE media_items SET path = ?, updated_ms = ? WHERE id = ?")

(def clear-path
  "Parameters: updated_ms id. The row keeps everything but its path."
  "UPDATE media_items SET path = NULL, updated_ms = ? WHERE id = ?")

;; ---------------------------------------------------------------------------
;; Paths and rows

(defn item-uri
  "The resource URI of media `id`."
  [id]
  (str "clogem://media/item/" id))

(defn normalize-path
  "`path` absolute and normalized, as a string; nil for a blank path."
  [path]
  (when (and (string? path) (not (str/blank? path)))
    (str (fs/normalize (fs/absolutize path)))))

(defn- not-found! [message data]
  (throw (ex-info message (merge {:type :media/not-found} data))))

(defn- require-file!
  "The normalized path of an existing regular file, or ex-info :media/not-found."
  [path]
  (let [p (normalize-path path)]
    (when-not (and p (fs/regular-file? p))
      (not-found! (str "media: not a readable file: " (pr-str (or p path))) {:path (or p path)}))
    p))

(defn find-by-id
  "The media_items row with `id`, or nil."
  [ctx id]
  (when (string? id)
    (first (api/query ctx [select-by-id id]))))

(defn find-by-path
  "The media_items row whose path is the normalized `path`, or nil."
  [ctx path]
  (when (string? path)
    (first (api/query ctx [select-by-path path]))))

(defn- tx!
  "Run `statements` as one transaction, or ex-info :media/db-error."
  [ctx statements]
  (let [reply (api/submit-tx! ctx statements)]
    (when-not (:ok reply)
      (throw (ex-info (str "media: database write failed: "
                           (or (get-in reply [:error :message]) (pr-str (:error reply))))
                      {:type :media/db-error :error (:error reply)})))
    reply))

(defn probe->json
  "A probe result as the JSON text stored in the `probe` column
   ({\"format\": …, \"streams\": […], \"duration_ms\": n})."
  [{:keys [format streams duration-ms]}]
  (json/generate-string {:format format :streams streams :duration_ms duration-ms}))

(defn- parse-json [s]
  (when (string? s)
    (try (let [v (json/parse-string s true)] (when (map? v) v))
         (catch Exception _ nil))))

(defn stored-probe
  "The probe stored in `row`, shaped as probe/probe! returns it, or nil."
  [row]
  (when-let [doc (parse-json (:probe row))]
    {:format (or (:format doc) {})
     :streams (vec (:streams doc))
     :duration-ms (:duration_ms doc)}))

(defn row->item
  "The row as plain data for JSON: its columns as they are, `probe` parsed
   into the stored document (nil when none)."
  [row]
  (-> (select-keys row [:id :path :size :fingerprint :duration_ms :created_ms :updated_ms])
      (assoc :probe (parse-json (:probe row)))))

(defn item-json
  "The row as JSON text, for clogem://media/item/{id}."
  [row]
  (json/generate-string (row->item row)))

(defn- unchanged?
  "True when the regular file at `path` still has the size and fingerprint
   recorded in `row`."
  [row path]
  (boolean (and (string? path)
                (fs/regular-file? path)
                (= (:size row) (fs/size path))
                (= (:fingerprint row) (fingerprint/fingerprint path)))))

;; ---------------------------------------------------------------------------
;; media_register

(defn register
  "media_register: register the file at :path and return
   {:id :path :size :duration-ms :fingerprint :created :relinked}.

   The path is made absolute and must be a regular file (else ex-info
   :media/not-found). The file is fingerprinted and probed (ffprobe gives
   the duration; a file without one counts as 0 ms, in the row, the id and
   the stored document alike) and the id derived.
   Then, in one transaction:
   - no row with this id → INSERT (created true);
   - a row with this id at this path → nothing changes;
   - a row with this id at another path, or without one → its path is set
     to this one (relinked true);
   and, in every writing case, a different row that held this path loses
   it first (its content was replaced). Each write publishes
   :media/registered {:id :path} and :resource/updated for every row that
   changed; an unchanged registration publishes nothing."
  [ctx {:keys [path]}]
  (let [path (require-file! path)
        size (fs/size path)
        fp (fingerprint/fingerprint path)
        probed (probe/probe! ctx path)
        duration (or (:duration-ms probed) 0)
        id (fingerprint/media-id fp duration)
        now (System/currentTimeMillis)
        by-id (find-by-id ctx id)
        by-path (find-by-path ctx path)
        displaced (when (and by-path (not= id (:id by-path))) (:id by-path))
        outcome (cond
                  (nil? by-id) :created
                  (= path (:path by-id)) :unchanged
                  :else :relinked)
        statements (cond-> []
                     displaced (conj [clear-path now displaced])
                     (= outcome :relinked) (conj [relink-item path now id])
                     (= outcome :created) (conj [insert-item id path size fp duration (probe->json (assoc probed :duration-ms duration)) now now]))]
    (when (seq statements)
      (tx! ctx statements)
      (api/publish! ctx :media/registered {:id id :path path})
      (doseq [changed (cond-> [id] displaced (conj displaced))]
        (api/publish! ctx :resource/updated {:uri (item-uri changed)})))
    {:id id
     :path path
     :size size
     :duration-ms duration
     :fingerprint fp
     :created (= outcome :created)
     :relinked (= outcome :relinked)}))

;; ---------------------------------------------------------------------------
;; media_probe

(defn- invalid! [message]
  (throw (ex-info message {:type :media/invalid-arguments})))

(defn- probe-file
  "The probe stored in `row` while the file at `path` is unchanged, else a
   fresh ffprobe run (never stored: media_probe is read-only)."
  [ctx row path]
  (or (when (and row (unchanged? row path)) (stored-probe row))
      (probe/probe! ctx path)))

(defn probe
  "media_probe: {:format :streams :duration-ms} for :id (a registered item,
   whose file must still be where its row says) or :path (any regular
   file), exactly one of them. ex-info :media/invalid-arguments for
   neither or both, :media/not-found for an unknown id, a missing file or
   a row whose path is gone."
  [ctx {:keys [id path]}]
  (let [result (cond
                 (and id path)
                 (invalid! "media_probe takes either id or path, not both")

                 id
                 (let [row (or (find-by-id ctx id)
                               (not-found! (str "media: unknown id " (pr-str id)) {:id id}))
                       p (:path row)]
                   (when-not (and (string? p) (fs/regular-file? p))
                     (not-found! (str "media: the file of " id " is missing"
                                      (when p (str " at " (pr-str p)))
                                      "; register it again from its current location")
                                 {:id id :path p}))
                   (probe-file ctx row p))

                 path
                 (let [p (require-file! path)]
                   (probe-file ctx (find-by-path ctx p) p))

                 :else
                 (invalid! "media_probe needs an id or a path"))]
    {:format (or (:format result) {})
     :streams (vec (:streams result))
     :duration-ms (or (:duration-ms result) 0)}))
