;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.writer
  "The single writer (PD-3). It owns the bus command :db/tx, so its one
   owner thread drains a bounded queue of transactions; nothing else in the
   daemon mutates the database.

   A transaction is {:module id :statements [stmt …]} where a statement is
   a SQL string or [sql & params]. It becomes ONE pod call:

     PRAGMA busy_timeout=<n>; PRAGMA foreign_keys=ON;
     BEGIN IMMEDIATE; <statements>; COMMIT;

   with the parameters concatenated in order. A failing statement rolls
   the whole transaction back (the pod closes its connection). Statements
   may not carry transaction control, pragmas, EXPLAIN or ATTACH, hold one
   statement each, close their comments and literals, and carry exactly as
   many parameters as `?` placeholders (parameters are bound across the
   batch in order, so a mismatch would shift every later statement);
   migration transactions (:migration? true, hub use) relax the
   one-statement rule because migration files hold several.

   Replies: {:ok true :result {:rows-affected n :last-inserted-id id
   :statements n}} or {:ok false :error {:type :invalid-tx|:sql-error|
   :busy|:pod-timeout|:pod-crashed …}}."
  (:require [clojure.string :as str]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.sql :as sql]
            [clogem.hub.db.sqlite :as sqlite]
            [clogem.hub.log :as log]))

(def defaults
  {:busy-timeout-ms 5000    ; SQLite busy handler inside each transaction
   :queue 256               ; bounded transaction queue
   :call-timeout-ms 30000   ; watchdog per pod call
   :tx-timeout-ms 60000})   ; how long submit! waits for queue room plus the reply

(defn tx-problems
  "Why `tx` cannot be executed, or an empty vector."
  [{:keys [statements migration?]}]
  (cond
    (not (sequential? statements)) [":statements must be a vector"]
    (empty? statements) [":statements is empty"]
    :else (vec (mapcat (fn [i s] (map #(str "statement " i " " %) (sql/statement-problems s (boolean migration?))))
                       (range) statements))))

(defn compose
  "The single [sql & params] a valid transaction runs as."
  [{:keys [statements]} busy-timeout-ms]
  (let [texts (map (fn [s] (sql/strip-trailing-semicolons (sqlite/sql-text s))) statements)
        params (into [] (mapcat sqlite/sql-params) statements)]
    (into [(str "PRAGMA busy_timeout=" (long busy-timeout-ms) "; PRAGMA foreign_keys=ON;\n"
                "BEGIN IMMEDIATE;\n"
                ;; each ';' on its own line: a trailing line comment in a
                ;; statement cannot swallow the separator or COMMIT
                (str/join "\n;\n" texts) "\n;\n"
                "COMMIT;")]
          params)))

(defn- handle-tx [{:keys [db busy-timeout-ms call-timeout-ms stats]} {:keys [module statements] :as tx}]
  (let [problems (tx-problems tx)]
    (if (seq problems)
      (do (swap! stats update :rejected inc)
          {:ok false :error {:type :invalid-tx :problems problems}})
      (try
        (let [r (sqlite/execute! db (compose tx busy-timeout-ms) {:timeout-ms call-timeout-ms})]
          (swap! stats update :committed inc)
          {:ok true :result (assoc r :statements (count statements))})
        (catch Exception e
          (swap! stats update :failed inc)
          (log/warn {:msg "transaction failed" :module module :type (:type (ex-data e)) :error (ex-message e)})
          {:ok false :error {:type (or (:type (ex-data e)) :sql-error) :message (ex-message e)}})))))

(defn start!
  "Start the writer for the database file `db` as owner of :db/tx on `bus`.
   Options override `defaults`. Returns the writer."
  [{:keys [bus db] :as opts}]
  (when-not (string? db) (throw (ex-info "writer needs a database path" {:type :no-db})))
  (let [opts (merge defaults opts)
        writer (assoc opts :stats (atom {:committed 0 :failed 0 :rejected 0}))
        unregister (bus/register-owner! bus :db/tx (partial handle-tx writer) {:buffer (:queue opts)})]
    (log/info {:msg "writer started" :db db :queue (:queue opts)})
    (assoc writer :unregister unregister)))

(defn submit!
  "Queue `tx` and wait for its reply (bounded by :tx-timeout-ms)."
  ([writer tx] (submit! writer tx {}))
  ([{:keys [bus tx-timeout-ms]} tx {:keys [timeout-ms]}]
   (bus/request! bus (assoc tx :command :db/tx) {:timeout-ms (or timeout-ms tx-timeout-ms)})))

(defn stats [writer]
  (assoc @(:stats writer) :pod (sqlite/stats)))

(defn stop!
  "Stop accepting transactions and finish the queued ones (drain)."
  [{:keys [unregister]}]
  (let [drained? (unregister)]
    (log/info {:msg "writer stopped" :drained (boolean drained?)})
    drained?))
