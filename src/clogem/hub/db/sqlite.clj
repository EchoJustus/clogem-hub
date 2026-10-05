;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.sqlite
  "The one namespace that loads the SQLite pod (PD-3: only clogem.hub.db.*
   may; `bb lint` enforces it). Every other database namespace calls the pod
   through `execute!` and `query` here.

   What the pod (org.babashka/go-sqlite3) does, measured in S02 and recorded
   in docs/adr/0003-single-writer-and-pod-semantics.md:
   - a fresh connection per call, so pragmas live only inside the call they
     start (preambles) and WAL mode, which is persistent, is set once;
   - a batch of statements in one `execute!` runs them all but is not
     atomic unless the batch itself says BEGIN … COMMIT; a connection closed
     mid-transaction rolls back;
   - parameters are consumed across the statements of a batch in order;
   - `query` prepares every statement of a batch but steps only the last
     one; flag pragmas still apply because SQLite applies them at prepare;
   - a blank statement hangs the pod and concurrent calls can deadlock it.

   Hence this gateway: SQL is validated (never blank), every call is
   serialized through one lock, runs under a watchdog, and a pod that hangs
   or dies is replaced (its process destroyed, the pod loaded again) so the
   daemon survives. Stats count calls, SQLITE_BUSY errors, timeouts,
   crashes and restarts."
  (:require [babashka.pods :as pods]
            [clojure.string :as str]
            [clogem.hub.log :as log]))

(def pod-name 'org.babashka/go-sqlite3)
(def pod-version "0.2.8")
(def ^:private pod-executable "pod-babashka-go-sqlite3")

(def default-call-timeout-ms 30000)

(defonce ^:private state
  (atom {:generation 0 :calls 0 :busy 0 :timeouts 0 :crashes 0 :restarts 0}))

(defonce ^:private gate (Object.))
(defonce ^:private restart-lock (Object.))

(defn- load! [] (pods/load-pod pod-name pod-version))

(load!)

(defn- pod-fn [sym]
  ;; resolved at call time: a reloaded pod re-binds its vars
  (or (some-> (resolve sym) deref)
      (throw (ex-info "sqlite pod function not loaded" {:type :pod-missing :fn sym}))))

(defn stats [] @state)

;; ---------------------------------------------------------------------------
;; SQL shape

(defn sql-text
  "The SQL string of `sql`, which is a string or [sql & params]."
  [sql]
  (if (vector? sql) (first sql) sql))

(defn sql-params [sql]
  (if (vector? sql) (vec (rest sql)) []))

(defn validate-sql!
  "Throw {:type :invalid-sql} unless `sql` is a non-blank string or a vector
   whose first element is one. A blank statement would hang the pod."
  [sql]
  (let [text (sql-text sql)]
    (when-not (and (string? text) (not (str/blank? text)))
      (throw (ex-info "SQL must be a non-blank string or [sql & params]" {:type :invalid-sql :sql sql})))
    sql))

;; ---------------------------------------------------------------------------
;; Pod process management

(defn- pod-processes []
  (->> (.children (java.lang.ProcessHandle/current))
       .iterator iterator-seq
       (filter (fn [^java.lang.ProcessHandle h]
                 (let [cmd (-> h .info .command (.orElse ""))]
                   (str/includes? cmd pod-executable))))))

(defn- restart!
  "Replace the pod process unless another thread already did so for this
   generation. Returns the new generation."
  [generation reason]
  (locking restart-lock
    (if (= generation (:generation @state))
      (do (log/warn {:msg "restarting the sqlite pod" :reason reason :generation generation})
          (doseq [^java.lang.ProcessHandle p (pod-processes)] (.destroyForcibly p))
          (load!)
          (:generation (swap! state (fn [s] (-> s (update :generation inc) (update :restarts inc))))))
      (:generation @state))))

(defn- crash? [^Throwable t]
  (let [m (or (ex-message t) "")]
    (or (instance? java.io.IOException t)
        (str/includes? m "Pod quit unexpectedly")
        (str/includes? m "Stream closed"))))

(defn- busy? [^Throwable t]
  (let [m (or (ex-message t) "")]
    (or (str/includes? m "database is locked") (str/includes? m "SQLITE_BUSY"))))

(defn- classify
  "Turn a pod exception into the ex-info the callers see."
  [^Throwable t op]
  (cond
    (crash? t) (ex-info "sqlite pod died during a call" {:type :pod-crashed :op op :message (ex-message t)} t)
    (busy? t) (ex-info (ex-message t) {:type :busy :op op} t)
    :else (ex-info (or (ex-message t) "sqlite error") {:type :sql-error :op op :message (ex-message t)} t)))

(defn- call!
  "Run `(f db sql)` serialized, under a watchdog of `timeout-ms`."
  [op fsym db sql {:keys [timeout-ms] :or {timeout-ms default-call-timeout-ms}}]
  (validate-sql! sql)
  (let [f (pod-fn fsym)
        generation (:generation @state)]
    (swap! state update :calls inc)
    (locking gate
      (let [fut (future (f db sql))
            r (try (deref fut timeout-ms ::timeout)
                   (catch java.util.concurrent.ExecutionException e (.getCause e)))]
        (cond
          (= r ::timeout)
          (do (swap! state update :timeouts inc)
              (restart! generation :timeout)
              (throw (ex-info (str "sqlite pod call exceeded " timeout-ms " ms")
                              {:type :pod-timeout :op op :timeout-ms timeout-ms})))

          (instance? Throwable r)
          (let [^Throwable t r
                e (classify t op)]
            (case (:type (ex-data e))
              :pod-crashed (do (swap! state update :crashes inc) (restart! generation :crash))
              :busy (swap! state update :busy inc)
              nil)
            (throw e))

          :else r)))))

;; ---------------------------------------------------------------------------
;; Public gateway

(defn execute!
  "Run `sql` (a string or [sql & params], several statements allowed) on the
   database file `db`. Returns {:rows-affected n :last-inserted-id id} of
   the last statement that changed rows."
  ([db sql] (execute! db sql {}))
  ([db sql opts] (call! :execute 'pod.babashka.go-sqlite3/execute! db sql opts)))

(defn query
  "Run `sql` on `db` and return its rows as maps with keyword keys. Of a
   multi-statement string only the last statement is stepped."
  ([db sql] (query db sql {}))
  ([db sql opts] (call! :query 'pod.babashka.go-sqlite3/query db sql opts)))
