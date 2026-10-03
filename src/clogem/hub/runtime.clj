;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.runtime
  "The hub's implementation of clogem.api/Runtime.

   S01 scope: publish! wraps and logs events (the bus arrives in S02),
   request! answers the registry commands the `system` module needs,
   config and log work. Database, processes, LLM and jobs throw a clear
   `:unsupported` error until S02/S04."
  (:require [clogem.api :as api]
            [clogem.sdk.event :as event]
            [clogem.hub.log :as log]))

(defn- unsupported [op session]
  (throw (ex-info (str (name op) " is not available until " session)
                  {:type :unsupported :op op :available-from session})))

(defn- registry-of [reg-ref]
  (or @reg-ref (throw (ex-info "runtime has no registry yet" {:type :no-registry}))))

(defn- answer [reg-ref command]
  ;; Registry projections are read through functions resolved at call time so
  ;; that this namespace does not depend on the registry at load time.
  (let [status (requiring-resolve 'clogem.hub.registry/status)
        summaries (requiring-resolve 'clogem.hub.registry/manifest-summaries)
        reg (registry-of reg-ref)]
    (case (:command command)
      :system/health {:ok true :result (status reg)}
      :system/modules {:ok true :result {:modules (summaries reg)}}
      {:ok false :error {:type :unknown-command :command (:command command)}})))

(defrecord HubRuntime [reg-ref config]
  api/Runtime
  (-publish! [_ ctx event-type payload]
    (let [e (event/event (:module/id ctx) event-type payload)]
      (log/debug {:msg "event" :event/type event-type :event/source (:module/id ctx) :event/id (:event/id e)})
      e))
  (-subscribe! [_ ctx event-type _handler]
    (log/debug {:msg "subscribe (no bus until S02)" :module (:module/id ctx) :event/type event-type})
    (fn unsubscribe [] nil))
  (-request! [_ _ctx command] (answer reg-ref command))
  (-submit-tx! [_ _ _] (unsupported :submit-tx! "S02"))
  (-query [_ _ _] (unsupported :query "S02"))
  (-run-process! [_ _ _ _] (unsupported :run-process! "S04"))
  (-llm-chat! [_ _ _] (unsupported :llm-chat! "S04"))
  (-job! [_ _ _ _] (unsupported :job! "S02"))
  (-config [_ ctx path] (get-in config (into [:modules (:module/id ctx)] path)))
  (-log [_ ctx level e] (log/log! level (assoc e :module (:module/id ctx)))))

(defn new-runtime
  "A runtime for `config`. Call `attach-registry!` once the registry exists."
  [config]
  (->HubRuntime (atom nil) config))

(defn attach-registry!
  "Point the runtime at the registry whose modules it serves."
  [^HubRuntime rt registry]
  (reset! (:reg-ref rt) registry)
  rt)
