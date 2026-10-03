;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.registry
  "The module registry: an atom of module entries keyed by module id.

   register!: validate the manifest with the SDK → check registry-wide
   uniqueness → resolve :module/entry and every handler symbol → start →
   status :ready, :degraded (started, health not ok) or :unavailable (with a
   reason). Ordering is deterministic: module id, then tool name.
   Projections: tools-for <profile>, resource-index. A :registry/changed
   callback seam notifies listeners; the bus replaces it in S02."
  (:require [clogem.sdk.manifest :as manifest]
            [clogem.hub.log :as log]))

(defn new-registry
  "Create a registry. `runtime` is the clogem.api/Runtime handed to modules
   (as :clogem/runtime in their ctx); `on-change` is a (fn [event]) seam."
  [{:keys [runtime on-change]}]
  (atom {:modules {} :runtime runtime :on-change on-change}))

(defn- notify! [reg event]
  (when-let [f (:on-change @reg)]
    (try (f (assoc event :event/type :registry/changed))
         (catch Exception e
           (log/warn {:msg "registry listener failed" :error (ex-message e)})))))

(defn module-ctx
  "The ctx a module receives: runtime, its id and its manifest."
  [reg id manifest]
  {:clogem/runtime (:runtime @reg) :module/id id :module/manifest manifest})

(defn- resolve-var
  "The current value of the var `sym` names, or nil."
  [sym]
  (try (some-> (requiring-resolve sym) deref) (catch Exception _ nil)))

(defn- resolve-handlers
  "{:tools {name fn} :resources {uri fn} :templates {uri-template fn}
    :prompts {name fn} :routes {action fn}} or {:missing [sym ...]}."
  [manifest]
  (let [groups {:tools [(get-in manifest [:mcp :tools]) :name]
                :resources [(get-in manifest [:mcp :resources]) :uri]
                :templates [(get-in manifest [:mcp :resource-templates]) :uri-template]
                :prompts [(get-in manifest [:mcp :prompts]) :name]
                :routes [(get-in manifest [:ui :routes]) :action]}
        resolved (into {} (for [[k [items key-fn]] groups]
                            [k (into {} (for [item items]
                                          [(get item key-fn) (resolve-var (:handler item))]))]))
        missing (for [[_ m] resolved [_ v] m :when (nil? v)] true)]
    (if (seq missing)
      {:missing (vec (for [[_ [items _]] groups
                           {:keys [handler]} items
                           :when (nil? (resolve-var handler))]
                       handler))}
      resolved)))

(defn- unavailable [id manifest reason]
  (log/warn {:msg "module unavailable" :module id :reason reason})
  {:id id :manifest manifest :status :unavailable :reason reason})

(defn- start-module [reg manifest]
  (let [id (:module/id manifest)
        {:keys [ok? problems]} (manifest/check manifest)]
    (if-not ok?
      (unavailable id manifest {:type :invalid-manifest :problems problems})
      (let [entry (resolve-var (:module/entry manifest))
            handlers (resolve-handlers manifest)]
        (cond
          (nil? entry)
          (unavailable id manifest {:type :entry-unresolved :symbol (:module/entry manifest)})

          (not (and (map? entry) (fn? (:start entry))))
          (unavailable id manifest {:type :bad-entry :symbol (:module/entry manifest)})

          (:missing handlers)
          (unavailable id manifest {:type :handlers-unresolved :symbols (:missing handlers)})

          :else
          (try
            (let [ctx (module-ctx reg id manifest)
                  state ((:start entry) ctx)
                  health (when-let [h (:health entry)] (h state))
                  ok (= :ok (get health :status :ok))]
              (log/info {:msg "module started" :module id :status (if ok :ready :degraded)})
              {:id id :manifest manifest :entry entry :state state :handlers handlers
               :status (if ok :ready :degraded)
               :reason (when-not ok {:type :health :health health})})
            (catch Exception e
              (unavailable id manifest {:type :start-failed :message (ex-message e)}))))))))

(defn- serving? [entry] (contains? #{:ready :degraded} (:status entry)))

(defn register!
  "Register a manifest (a map). Returns the module entry; its :status says
   whether the module is :ready, :degraded or :unavailable (see :reason)."
  [reg manifest]
  (let [id (:module/id manifest)
        ;; only serving modules own names; an unavailable entry exposes nothing
        others (->> (vals (dissoc (:modules @reg) id)) (filter serving?) (map :manifest))
        conflicts (when (keyword? id) (manifest/check-registry (conj (vec others) manifest)))
        entry (if (seq conflicts)
                (unavailable id manifest {:type :conflict :problems conflicts})
                (start-module reg manifest))]
    (swap! reg assoc-in [:modules id] entry)
    (notify! reg {:change :registered :module id :status (:status entry)})
    entry))

(defn- stop-entry! [{:keys [id entry state]}]
  (when-let [stop (:stop entry)]
    (try (stop state)
         (catch Exception e
           (log/warn {:msg "module stop failed" :module id :error (ex-message e)})))))

(defn unregister!
  "Stop and remove a module. Returns true when it was registered."
  [reg id]
  (if-let [entry (get-in @reg [:modules id])]
    (do (stop-entry! entry)
        (swap! reg update :modules dissoc id)
        (notify! reg {:change :unregistered :module id})
        true)
    false))

(defn stop-all!
  "Stop every module, in reverse id order."
  [reg]
  (doseq [id (reverse (sort (keys (:modules @reg))))]
    (unregister! reg id)))

(defn modules
  "Module entries ordered by id."
  [reg]
  (->> (:modules @reg) vals (sort-by (comp name :id))))

(defn module-ids [reg] (mapv :id (modules reg)))

(defn status
  "{:status :ok|:degraded :modules [{:id :status :reason} ...]}: :ok only
   when every registered module is :ready."
  [reg]
  (let [ms (modules reg)]
    {:status (if (every? #(= :ready (:status %)) ms) :ok :degraded)
     :modules (mapv #(select-keys % [:id :status :reason]) ms)}))

(defn tools-for
  "Tools exposed to `profile`, ordered by module id then tool name:
   [{:module-id id :tool manifest-tool :handler fn :ctx ctx} ...]."
  [reg profile]
  (vec (for [{:keys [id manifest handlers] :as entry} (modules reg)
             :when (serving? entry)
             tool (sort-by :name (get-in manifest [:mcp :tools]))
             :when (contains? (:profiles tool) profile)]
         {:module-id id
          :tool tool
          :handler (get-in handlers [:tools (:name tool)])
          :ctx (module-ctx reg id manifest)})))

(defn find-tool
  "The tools-for entry named `tool-name` for `profile`, or nil."
  [reg profile tool-name]
  (first (filter #(= tool-name (get-in % [:tool :name])) (tools-for reg profile))))

(defn resource-index
  "{:resources {uri {:module-id :resource :handler :ctx}}
    :templates [{:module-id :template :handler :ctx} ...]} for `profile`."
  [reg profile]
  (let [served (filter serving? (modules reg))]
    {:resources (into (sorted-map)
                      (for [{:keys [id manifest handlers]} served
                            r (get-in manifest [:mcp :resources])
                            :when (contains? (:profiles r) profile)]
                        [(:uri r) {:module-id id :resource r
                                   :handler (get-in handlers [:resources (:uri r)])
                                   :ctx (module-ctx reg id manifest)}]))
     :templates (vec (for [{:keys [id manifest handlers]} served
                           t (sort-by :uri-template (get-in manifest [:mcp :resource-templates]))
                           :when (contains? (:profiles t) profile)]
                       {:module-id id :template t
                        :handler (get-in handlers [:templates (:uri-template t)])
                        :ctx (module-ctx reg id manifest)}))}))

(defn manifest-summaries
  "[{:id :version :status :tools n :description} ...] for system tools."
  [reg]
  (mapv (fn [{:keys [id manifest status]}]
          {:id (name id)
           :version (:module/version manifest)
           :status (name status)
           :tools (count (get-in manifest [:mcp :tools]))
           :description (:module/description manifest)})
        (modules reg)))
