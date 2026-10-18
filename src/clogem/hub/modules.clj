;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.modules
  "External modules: proprietary modules may be loaded at runtime from
   `modules.edn` in the configuration directory (or `:modules-file`),
   after the built-in ones. The file lists module checkouts by root:

     {:modules [{:id :demo :root \"D:\\\\modules\\\\demo\\\\module\" :enabled? true}
                {:id :other :root \"../other/module\"}]}

   `:id` is the module id (an unqualified keyword, the same as the
   manifest's `:module/id`); `:root` is a directory, absolute or relative
   to the directory of modules.edn (a Windows drive-letter path is just a
   string with escaped backslashes); `:enabled?` defaults to true. A root
   holds `src/` and `resources/`, and the manifest lives at
   `root/resources/clogem/module/<id>/manifest.edn`.

   Loading an entry: the root and the manifest must exist, the manifest is
   read with clojure.edn (no eval), its id must equal the entry's id, then
   `src` and `resources` join the classpath (each directory once) and the
   manifest goes through `clogem.hub.registry/register!`, which validates
   it, resolves the entry and handlers under `clogem.module.<id>.` and
   starts the module. Every per-entry failure is logged and reported; the
   daemon never aborts over an external module. A missing modules.edn
   means zero external modules."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]
            [clogem.hub.registry :as registry]
            [clogem.sdk.manifest :as manifest]))

;; ---------------------------------------------------------------------------
;; modules.edn

(def Entry
  "Shape of one entry of `:modules`."
  [:map {:closed true}
   [:id manifest/ModuleId]
   [:root [:string {:min 1}]]
   [:enabled? {:optional true} :boolean]])

(defn- key-name [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- humanize
  "[\"key: message; message\" ...] from a malli humanize tree of a map value."
  [tree]
  (cond
    (map? tree) (vec (mapcat (fn [[k v]] (map #(str (key-name k) ": " %) (humanize v))) tree))
    (sequential? tree) [(str/join "; " (map str tree))]
    (nil? tree) []
    :else [(str tree)]))

(defn entry-problems
  "Humanized violations of the entry shape, or an empty vector."
  [entry]
  (if (map? entry)
    (if-let [ex (m/explain Entry entry)]
      (humanize (me/humanize ex))
      [])
    [(str "must be a map {:id :root :enabled?}, got " (pr-str entry))]))

(defn resolve-root
  "The absolute, normalized root directory string for an entry's `:root`,
   relative roots resolving against `base` (the directory of modules.edn)."
  [base root]
  (str (fs/normalize (fs/absolutize (fs/path base root)))))

(defn- normalize-entry [base index {:keys [id root enabled?] :or {enabled? true}}]
  {:id id
   :root (resolve-root base root)
   :declared-root root
   :enabled? enabled?
   :index index})

(defn- collect-entries
  "Validate and normalize the entries of a modules.edn value; the first
   entry of a duplicated id wins."
  [base entries]
  (reduce (fn [acc [i e]]
            (let [problems (entry-problems e)
                  where (str "modules[" i "]: ")]
              (cond
                (seq problems)
                (update acc :problems into (map #(str where %) problems))

                (contains? (:seen acc) (:id e))
                (update acc :problems conj (str where "duplicate module id " (:id e) " (the first entry wins)"))

                :else
                (-> acc
                    (update :entries conj (normalize-entry base i e))
                    (update :seen conj (:id e))))))
          {:entries [] :problems [] :seen #{}}
          (map-indexed vector entries)))

(defn read-entries
  "Read modules.edn at `path`: {:entries [{:id :root :declared-root :enabled?
   :index} ...] :problems [string ...] :exists? bool :file string}. Entries
   with a bad shape are reported in :problems and left out; roots are
   absolute strings."
  [path]
  (let [file (str path)
        base {:entries [] :problems [] :file file}]
    (if-not (fs/exists? file)
      (assoc base :exists? false)
      (let [{:keys [value error]} (try {:value (config/read-edn-file file)}
                                       (catch Exception e {:error (ex-message e)}))
            entries (get value :modules [])]
        (cond
          error (assoc base :exists? true :problems [(str "cannot read " file ": " error)])
          (nil? value) (assoc base :exists? true :problems [(str file " is not an EDN map (or is empty)")])
          (not (sequential? entries))
          (assoc base :exists? true
                 :problems [(str ":modules must be a vector of entries, got " (pr-str entries))])
          :else
          (let [dir (fs/parent (fs/absolutize file))
                {:keys [entries problems]} (collect-entries dir entries)]
            (assoc base :exists? true :entries entries :problems problems)))))))

;; ---------------------------------------------------------------------------
;; Classpath

(defn- canonical-dir [p]
  (str (fs/normalize (fs/absolutize p))))

(defn- classpath-dirs []
  (set (map canonical-dir (some-> (cp/get-classpath) cp/split-classpath))))

(defn add-root!
  "Add `root`/src and `root`/resources (those that exist) to the classpath,
   each directory once, joined with the platform path separator. Returns
   the directories added by this call."
  [root]
  (let [present (classpath-dirs)
        wanted (for [d ["src" "resources"]
                     :let [p (fs/path root d)]
                     :when (fs/directory? p)]
                 (canonical-dir p))
        added (vec (remove present wanted))]
    (when (seq added)
      (cp/add-classpath (str/join java.io.File/pathSeparator added)))
    added))

;; ---------------------------------------------------------------------------
;; Loading

(defn manifest-path
  "Where an entry's manifest must live: root/resources/clogem/module/<id>/manifest.edn."
  [root id]
  (str (fs/path root "resources" "clogem" "module" (name id) "manifest.edn")))

(defn- failure [reason] {:ok? false :reason reason})

(defn- serving? [entry] (contains? #{:ready :degraded} (:status entry)))

(defn- register-entry! [reg manifest]
  (try
    (let [entry (registry/register! reg manifest)]
      (if (serving? entry)
        {:ok? true :status (:status entry)}
        (assoc (failure (:reason entry))
               :registered? (contains? (:modules @reg) (:module/id manifest))
               :status (:status entry))))
    (catch Exception e
      (failure {:type :register-failed :message (ex-message e) :data (ex-data e)}))))

(defn load-entry!
  "Load one normalized entry into `reg`: {:ok? true :status} or
   {:ok? false :reason {:type …}} (`:registered? true` when the registry
   holds the module as :unavailable). Reasons: :root-missing,
   :manifest-missing, :manifest-unreadable, :id-mismatch, the registry's
   own reasons (:invalid-manifest, :entry-unresolved, …) and :register-failed."
  [reg {:keys [id root]}]
  (let [mpath (manifest-path root id)]
    (cond
      (not (fs/directory? root))
      (failure {:type :root-missing :root root})

      (not (fs/regular-file? mpath))
      (failure {:type :manifest-missing :path mpath})

      :else
      (let [{:keys [manifest error]} (try {:manifest (manifest/read-manifest mpath)}
                                          (catch Exception e {:error (ex-message e)}))]
        (cond
          error
          (failure {:type :manifest-unreadable :path mpath :message error})

          (not= id (:module/id manifest))
          (failure {:type :id-mismatch :expected id :actual (:module/id manifest)})

          :else
          (do (add-root! root)
              (register-entry! reg manifest)))))))

(defn load-all!
  "Load every enabled entry of the modules.edn at `path` into `reg`.
   Returns {:loaded [ids] :failed [{:id :reason [:registered?]} ...]
   :skipped [ids] :problems [string ...]}: :loaded holds the modules that
   serve (:ready or :degraded), :failed the entries that did not load or
   are :unavailable, :skipped the disabled ones, :problems the file's shape
   problems. Nothing here throws over a module: every failure is logged
   with :module and :reason."
  [reg path]
  (let [{:keys [file exists? entries problems]} (read-entries path)
        report {:loaded [] :failed [] :skipped [] :problems (vec problems)}]
    (if-not exists?
      (do (log/info {:msg "no modules.edn, no external modules" :file file})
          report)
      (do
        (doseq [p problems]
          (log/warn {:msg "modules.edn problem" :file file :problem p}))
        (reduce (fn [report {:keys [id root enabled?] :as entry}]
                  (if-not enabled?
                    (do (log/info {:msg "external module disabled" :module id :root root})
                        (update report :skipped conj id))
                    (let [{:keys [ok? status reason registered?]}
                          (try (load-entry! reg entry)
                               (catch Exception e
                                 (failure {:type :load-failed :message (ex-message e) :data (ex-data e)})))]
                      (if ok?
                        (do (log/info {:msg "external module loaded" :module id :root root :status status})
                            (update report :loaded conj id))
                        (do (log/error {:msg "external module not loaded" :module id :root root
                                        :reason reason :registered? (boolean registered?)})
                            (update report :failed conj
                                    (cond-> {:id id :reason reason}
                                      registered? (assoc :registered? true))))))))
                report
                entries)))))
