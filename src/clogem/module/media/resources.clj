;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.resources
  "Resource handlers of the built-in `media` module. A handler takes the
   module ctx and {:uri uri :params {var value}} (template variables are
   strings) and returns {:mime-type m :text s}, or nil when there is nothing
   at that URI."
  (:require [clogem.module.media.tools :as tools]))

(defn item
  "clogem://media/item/{id}: the row of media `id` as JSON (see
   clogem.module.media.tools/row->item), or nil for an unknown id."
  [ctx {:keys [params]}]
  (when-let [row (tools/find-by-id ctx (str (:id params)))]
    {:mime-type "application/json" :text (tools/item-json row)}))
