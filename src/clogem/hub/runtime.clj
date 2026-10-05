;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.runtime
  "The hub's implementation of clogem.api/Runtime.

   S02 scope: publish!/subscribe! go through the bus and are checked against
   the module's manifest (`:bus/publishes` schemas, `:bus/subscribes`);
   request! is a bus command with a reply; submit-tx!, query and job! reach
   the store (single writer, read facade, jobs); config and log work.
   Processes and LLM throw a clear `:unsupported` error until S04."
  (:require [malli.core :as m]
            [malli.error :as me]
            [clogem.api :as api]
            [clogem.sdk.event :as event]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.store :as store]
            [clogem.hub.jobs :as jobs]
            [clogem.hub.log :as log]))

(defn- unsupported [op session]
  (throw (ex-info (str (name op) " is not available until " session)
                  {:type :unsupported :op op :available-from session})))

(defn- registry-of [reg-ref]
  (or @reg-ref (throw (ex-info "runtime has no registry yet" {:type :no-registry}))))

(defn- store-of [store]
  (or store (throw (ex-info "the database is not available in this runtime" {:type :no-store}))))

(defn answer
  "Reply to the registry commands the `system` module needs."
  [reg-ref command]
  ;; Registry projections are read through functions resolved at call time so
  ;; that this namespace does not depend on the registry at load time.
  (let [status (requiring-resolve 'clogem.hub.registry/status)
        summaries (requiring-resolve 'clogem.hub.registry/manifest-summaries)
        reg (registry-of reg-ref)]
    (case (:command command)
      :system/health {:ok true :result (status reg)}
      :system/modules {:ok true :result {:modules (summaries reg)}}
      {:ok false :error {:type :unknown-command :command (:command command)}})))

(def registry-commands
  "Commands owned by the runtime on behalf of the registry."
  [:system/health :system/modules])

(defn- check-publish!
  "A module may publish only the event types its manifest declares, with
   payloads valid against the declared schema. A ctx without a manifest
   (hand-built in tests) is not checked."
  [{:keys [module/manifest module/id]} event-type payload]
  (when manifest
    (let [publishes (get-in manifest [:bus :publishes])]
      (when-not (contains? publishes event-type)
        (throw (ex-info (str id " does not declare " event-type " in :bus/publishes")
                        {:type :undeclared-event :module id :event/type event-type :declared (set (keys publishes))})))
      (let [schema (get publishes event-type)]
        (when (and schema (not (m/validate schema payload)))
          (throw (ex-info (str "payload of " event-type " does not match its declared schema")
                          {:type :invalid-payload :module id :event/type event-type
                           :problems (me/humanize (m/explain schema payload))})))))))

(defn- check-subscribe! [{:keys [module/manifest module/id]} event-type]
  (when (and manifest (not (contains? (get-in manifest [:bus :subscribes]) event-type)))
    (throw (ex-info (str id " does not declare " event-type " in :bus/subscribes")
                    {:type :undeclared-subscription :module id :event/type event-type
                     :declared (get-in manifest [:bus :subscribes])}))))

(defn- statements-of [tx]
  (cond
    (map? tx) (:statements tx)
    (sequential? tx) (vec tx)
    :else tx))

(defrecord HubRuntime [reg-ref config bus store]
  api/Runtime
  (-publish! [_ ctx event-type payload]
    (check-publish! ctx event-type payload)
    (bus/publish! bus (event/event (:module/id ctx) event-type payload)))
  (-subscribe! [_ ctx event-type handler]
    (check-subscribe! ctx event-type)
    (bus/subscribe! bus event-type handler {:id [(:module/id ctx) event-type (random-uuid)]}))
  (-request! [_ _ctx command]
    (bus/request! bus command {:timeout-ms (get-in config [:bus :request-timeout-ms])}))
  (-submit-tx! [_ ctx tx]
    (store/tx! (store-of store) (:module/id ctx) (statements-of tx)))
  (-query [_ _ctx q]
    (store/query (store-of store) q))
  (-run-process! [_ _ _ _] (unsupported :run-process! "S04"))
  (-llm-chat! [_ _ _] (unsupported :llm-chat! "S04"))
  (-job! [_ ctx op job]
    (jobs/handle {:store (store-of store) :bus bus} (:module/id ctx) op job))
  (-config [_ ctx path] (get-in config (into [:modules (:module/id ctx)] path)))
  (-log [_ ctx level e] (log/log! level (assoc e :module (:module/id ctx)))))

(defn new-runtime
  "A runtime over `bus` (required) and `store` (nil until the daemon opened
   the database). Call `attach-registry!` once the registry exists."
  [{:keys [config bus store]}]
  (when-not bus (throw (ex-info "runtime needs a bus" {:type :no-bus})))
  (->HubRuntime (atom nil) (or config {}) bus store))

(defn attach-registry!
  "Point the runtime at the registry whose modules it serves and own the
   registry commands on the bus. Returns the runtime."
  [^HubRuntime rt registry]
  (reset! (:reg-ref rt) registry)
  (doseq [c registry-commands]
    (bus/register-owner! (:bus rt) c (partial answer (:reg-ref rt))))
  rt)
