;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.system.tools
  "Tool handlers of the built-in `system` module. Registry facts are
   obtained through clogem.api/request!, never by requiring the registry."
  (:require [clogem.api :as api]))

(defn- reply [ctx command]
  (let [{:keys [ok result error]} (api/request! ctx command)]
    (if ok
      result
      (throw (ex-info (str "command failed: " (pr-str (:type error))) {:error error})))))

(defn health
  "system_health: daemon status and per-module readiness."
  [ctx _args]
  (let [{:keys [status modules]} (reply ctx {:command :system/health})]
    {:status (name status)
     :modules (mapv (fn [{:keys [id status]}] {:id (name id) :status (name status)}) modules)}))

(defn list-modules
  "system_list_modules: every loaded module with version, status and tool count."
  [ctx _args]
  (reply ctx {:command :system/modules}))
