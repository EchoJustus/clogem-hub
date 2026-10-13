;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.core
  "Entry point of the built-in `media` module. Like every module it codes
   against clogem.api only and never requires clogem.hub.*.

   The registry does not check a manifest's :bins, so the :health hook does:
   it runs `ffprobe -version` through clogem.api/run-process! and reports
   {:status :ok} on exit 0, else {:status :degraded :error \"ffprobe not
   found\"} (ffprobe missing from :bins and PATH, a non-zero exit, or a
   runtime without a process function). A hub without ffprobe therefore
   lists media as :degraded, still serving its tools, and never crashes."
  (:require [clogem.api :as api]))

(def health-timeout-ms
  "How long `ffprobe -version` may take before the check gives up."
  10000)

(defn health
  "{:status :ok} when `ffprobe -version` exits 0, else {:status :degraded
   :error \"ffprobe not found\" …} with what went wrong."
  [ctx]
  (let [result (try
                 (let [{:keys [exit]} (api/run-process! ctx ["ffprobe" "-version"] {:timeout-ms health-timeout-ms})]
                   (if (= 0 exit)
                     {:status :ok}
                     {:status :degraded :error "ffprobe not found" :exit exit}))
                 (catch Exception e
                   (let [data (ex-data e)]
                     {:status :degraded
                      :error (if (= :process/binary-missing (:type data))
                               "ffprobe not found"
                               (str "ffprobe check failed: " (ex-message e)))
                      :type (:type data)})))]
    (when-not (= :ok (:status result))
      (api/log ctx :warn (assoc result :msg "media module degraded: ffprobe is not usable")))
    result))

(def module
  {:start (fn [ctx]
            (api/log ctx :info {:msg "media module started"})
            {:ctx ctx :started-at (System/currentTimeMillis)})
   :stop (fn [_state] nil)
   :health (fn [state] (health (:ctx state)))})
