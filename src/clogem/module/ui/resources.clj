;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.ui.resources
  "Resource handlers of the built-in `ui` module. A handler takes the
   module ctx and {:uri uri :params {var value}} and returns {:mime-type
   :text}."
  (:require [clogem.module.ui.inbox :as ui-inbox]))

(defn inbox
  "clogem://ui/inbox/{client}: the client's un-acknowledged commands,
   oldest first, as a JSON array of {id, command, created_ms}. `[]` for a
   client nobody wrote to: an empty inbox is a resource too, never nil."
  [ctx {:keys [params]}]
  {:mime-type "application/json" :text (ui-inbox/unacked-json ctx (:client params))})
