;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.reader
  "The read facade behind clogem.api/query. A query is one SELECT, WITH,
   VALUES or EXPLAIN statement (a string or [sql & params]) and runs as

     PRAGMA busy_timeout=<n>; PRAGMA query_only=ON; <query>

   in one pod call, on the caller's thread (the pod gateway serializes).
   The pod steps only the last statement, so a query may not carry a
   second one; SQLite applies the flag pragma at prepare time, so a write
   that slipped through would be refused."
  (:require [clogem.hub.db.sql :as sql]
            [clogem.hub.db.sqlite :as sqlite]))

(defn query
  "Rows of `q` on the database file `db`, as maps with keyword keys. Throws
   {:type :invalid-query} before touching the pod when `q` is not a single
   read-only statement, and the pod gateway's ex-info on errors."
  ([db q] (query db q {}))
  ([db q {:keys [busy-timeout-ms call-timeout-ms] :or {busy-timeout-ms 5000}}]
   (let [problems (sql/query-problems q)]
     (when (seq problems)
       (throw (ex-info (str "invalid query: " (first problems)) {:type :invalid-query :problems problems})))
     (sqlite/query db
                   (into [(str "PRAGMA busy_timeout=" (long busy-timeout-ms) "; PRAGMA query_only=ON;\n"
                               (sql/strip-trailing-semicolons (sqlite/sql-text q)))]
                         (sqlite/sql-params q))
                   (if call-timeout-ms {:timeout-ms call-timeout-ms} {})))))
