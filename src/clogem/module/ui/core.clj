;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.ui.core
  "Entry point of the built-in `ui` module: the inbox of commands other
   modules address to a GUI client. :start subscribes to `:ui/command`
   (declared in the manifest's :bus/subscribes) and :stop unsubscribes.
   Like every module it codes against clogem.api only and never requires
   clogem.hub.*."
  (:require [clogem.api :as api]
            [clogem.module.ui.inbox :as inbox]))

(defn on-command
  "The `:ui/command` handler: take {:client string :command map} from the
   envelope's payload, store it in the client's inbox and announce the
   inbox resource as updated. An invalid payload is logged and dropped."
  [ctx event]
  (inbox/receive! ctx (:event/payload event)))

(def module
  {:start (fn [ctx]
            (let [unsubscribe (api/subscribe! ctx :ui/command (partial on-command ctx))]
              (api/log ctx :info {:msg "ui module started"})
              {:started-at (System/currentTimeMillis) :unsubscribe unsubscribe}))
   :stop (fn [state]
           (when-let [unsubscribe (:unsubscribe state)]
             (unsubscribe))
           nil)
   :health (fn [_state] {:status :ok})})
