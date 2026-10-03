;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.log
  "The only namespace in the daemon and stdio paths that writes to stderr.
   One EDN map per line, never to stdout (PD-4): in a stdio MCP process
   stdout carries protocol frames only. Tokens and full transcripts are
   never logged; keys named like secrets are redacted before printing."
  (:require [clojure.string :as str]))

(def levels {:debug 0 :info 1 :warn 2 :error 3})

(defn- env-level []
  (let [raw (some-> (System/getenv "CLOGEM_LOG_LEVEL") str/lower-case keyword)]
    (if (contains? levels raw) raw :info)))

(def ^:private min-level (atom (env-level)))

(defn set-level!
  "Lowest level that is printed: :debug, :info (default), :warn or :error."
  [level]
  (when-not (contains? levels level)
    (throw (ex-info (str "unknown log level " level) {:levels (keys levels)})))
  (reset! min-level level))

(def ^:private secret-key-re #"(?i)(token|secret|password|authorization|bearer|api[-_]?key)")

(defn redact
  "Replace the values of secret-looking keys, at any depth, with :redacted."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k x]]
                             [k (if (re-find secret-key-re (name (if (keyword? k) k (str k))))
                                  :redacted
                                  (redact x))]))
                   v)
    (sequential? v) (mapv redact v)
    :else v))

(defn enabled? [level]
  (>= (get levels level 1) (get levels @min-level 1)))

(defn log!
  "Print `event` (a map) at `level` as one EDN line on stderr."
  [level event]
  (when (enabled? level)
    (let [line (pr-str (merge {:ts (str (java.time.Instant/now)) :level level}
                              (redact event)))]
      (binding [*out* *err*]
        (println line)
        (flush)))))

(defn debug [event] (log! :debug event))
(defn info [event] (log! :info event))
(defn warn [event] (log! :warn event))
(defn error [event] (log! :error event))
