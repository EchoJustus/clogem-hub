;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.audit
  "The `audit` table: who called what, when, with which outcome (ADR-0005).

   One row per tools/call, resources/read and subscriptions/listen request
   and per rejected authentication. Never the arguments, the result, the
   token or anything else a transcript could leak through. Rows are queued
   on a bounded channel and written by one thread through the single writer
   (`:db/tx` on behalf of :hub), so a request never waits for the database
   and a flooded queue drops rows (counted) instead of blocking."
  (:require [clojure.core.async :as async]
            [clogem.hub.db.store :as store]
            [clogem.hub.log :as log]))

(def ^:private insert-sql
  "INSERT INTO audit (ts, client, profile, method, name, ok, error, duration_ms, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")

(defn- row
  "[sql & params] for one audit entry."
  [{:keys [ts client profile method name ok? error duration-ms request-id]}]
  [insert-sql
   (or ts (System/currentTimeMillis))
   (str (or client "-"))
   (clojure.core/name (or profile :none))
   (str method)
   (some-> name str)
   (if ok? 1 0)
   (some-> error str (subs 0 (min 200 (count (str error)))))
   (long (or duration-ms 0))
   (some-> request-id str)])

(defn start!
  "Start the audit writer over `store`. Returns the handle `record!` and
   `stop!` take."
  [store {:keys [queue] :or {queue 256}}]
  (let [ch (async/chan queue)
        stats (atom {:recorded 0 :dropped 0 :failed 0})
        thread (async/thread
                 (loop []
                   (when-let [entry (async/<!! ch)]
                     (try
                       (let [{:keys [ok]} (store/tx! store :hub [(row entry)])]
                         (if ok
                           (swap! stats update :recorded inc)
                           (do (swap! stats update :failed inc)
                               (log/warn {:msg "audit row not written" :method (:method entry)}))))
                       (catch Exception e
                         (swap! stats update :failed inc)
                         (log/warn {:msg "audit row not written" :error (ex-message e)})))
                     (recur))))]
    {:ch ch :thread thread :stats stats}))

(defn record!
  "Queue one entry: {:client :profile :method :name :ok? :error :duration-ms
   :request-id}. Non-blocking; a full queue drops the entry. nil-safe."
  [audit entry]
  (when audit
    (when-not (async/offer! (:ch audit) (assoc entry :ts (System/currentTimeMillis)))
      (swap! (:stats audit) update :dropped inc)
      (log/warn {:msg "audit queue full, entry dropped" :method (:method entry)}))))

(defn stats [audit] (some-> audit :stats deref))

(defn stop!
  "Stop accepting entries and write the queued ones (drain, bounded)."
  [{:keys [ch thread]}]
  (when ch
    (async/close! ch)
    (async/alts!! [thread (async/timeout 10000)])
    nil))

(defn recent
  "The last `n` audit rows, newest first (tests and diagnostics)."
  [store n]
  (store/query store (str "SELECT id, ts, client, profile, method, name, ok, error, duration_ms, request_id "
                          "FROM audit ORDER BY id DESC LIMIT " (long n))))
