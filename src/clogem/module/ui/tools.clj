;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.ui.tools
  "Tool handlers of the built-in `ui` module."
  (:require [clogem.module.ui.inbox :as inbox]))

(defn ack
  "ui_ack: acknowledge command `id` in `client`'s inbox. {:acked true} when
   the row exists for that client (acknowledging twice is harmless and
   changes nothing), {:acked false} when it does not."
  [ctx {:keys [client id]}]
  {:acked (inbox/ack! ctx client id)})
