;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.system.core
  "Entry point of the built-in `system` module. Like every module it codes
   against clogem.api only and never requires clogem.hub.*."
  (:require [clogem.api :as api]))

(def module
  {:start (fn [ctx]
            (api/log ctx :info {:msg "system module started"})
            {:started-at (System/currentTimeMillis)})
   :stop (fn [_state] nil)
   :health (fn [_state] {:status :ok})})
