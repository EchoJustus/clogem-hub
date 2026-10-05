;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.jobs
  "Jobs: long-running work a module reports on so that GUIs and MCP clients
   can follow it (clogem.api/job!). Rows live in the hub's `jobs` table
   (migration 0001) and change only through the single writer; every change
   is also published on the bus as :job/created, :job/progress,
   :job/completed, :job/failed or :job/cancelled with the job map as
   payload (source :hub).

   Lifecycle: queued → running → done | failed | cancelled. :progress moves a
   queued job to running and carries a fraction 0..1 plus a message. :cancel
   finishes the job at once; the next :progress (or :complete/:fail) from the
   module is answered {:type :job-finished :status :cancelled}, which is how
   a running module learns to stop. A finished job rejects every further
   change with :job-finished. A module may only touch its own jobs; the hub
   (module :hub) may touch any.

   Ops: :create {:kind :input} · :progress {:id :progress :message} ·
   :complete {:id :result} · :fail {:id :error} · :cancel {:id} · :get {:id}.
   Input, result and error are stored as EDN text and read back with
   clojure.edn."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.store :as store]
            [clogem.sdk.event :as event]))

(def ops #{:create :progress :complete :fail :cancel :get})

(def statuses #{:queued :running :done :failed :cancelled})

(def ^:private open-statuses "status IN ('queued', 'running')")

(defn- now [] (System/currentTimeMillis))

(defn- edn-str
  "`v` as EDN text, or an :invalid-job throw when pr-str cannot round-trip
   it (records, functions, objects): the reader would hand back a string."
  [v]
  (when (some? v)
    (let [s (pr-str v)]
      (try (edn/read-string {:eof nil} s)
           (catch Exception _
             (throw (ex-info "job data must be plain EDN (maps, vectors, strings, numbers, keywords)"
                             {:type :invalid-job :problems ["value is not plain EDN"]}))))
      s)))

(defn- read-edn
  "Our own EDN text back to data; the raw string when it does not read."
  [s]
  (when (string? s)
    (try (edn/read-string {:eof nil} s) (catch Exception _ s))))

(defn row->job [row]
  (when row
    {:id (:id row)
     :module (keyword (:module row))
     :kind (:kind row)
     :status (keyword (:status row))
     :progress (double (:progress row))
     :message (:message row)
     :input (read-edn (:input row))
     :result (read-edn (:result row))
     :error (read-edn (:error row))
     :cancel-requested? (= 1 (:cancel_requested row))
     :created-at (:created_at row)
     :updated-at (:updated_at row)
     :finished-at (:finished_at row)}))

(defn get-job
  "The job `id`, or nil."
  [store id]
  (row->job (first (store/query store ["SELECT * FROM jobs WHERE id = ?" id]))))

(defn list-jobs
  "Jobs newest first; `:module` and `:status` filter, `:limit` defaults to 100."
  [store {:keys [module status limit] :or {limit 100}}]
  (let [clauses (cond-> [] module (conj ["module = ?" (name module)]) status (conj ["status = ?" (name status)]))
        where (when (seq clauses) (str " WHERE " (str/join " AND " (map first clauses))))]
    (mapv row->job
          (store/query store (into [(str "SELECT * FROM jobs" where " ORDER BY updated_at DESC, id LIMIT ?")]
                                   (concat (map second clauses) [(long limit)]))))))

(defn- owner? [job module] (or (= :hub module) (= module (:module job))))

(defn- invalid [& problems] {:ok false :error {:type :invalid-job :problems (vec problems)}})

(defn- publish! [bus etype job]
  (when bus (bus/publish! bus (event/event :hub etype job)))
  job)

(defn- explain-no-change [store module id]
  (let [job (get-job store id)]
    (cond
      (nil? job) {:ok false :error {:type :not-found :id id}}
      (not (owner? job module)) {:ok false :error {:type :not-owner :id id :owner (:module job)}}
      :else {:ok false :error {:type :job-finished :id id :status (:status job)}})))

(def ^:private event-status
  {:job/progress :running :job/completed :done :job/failed :failed :job/cancelled :cancelled})

(defn- change!
  "Run one UPDATE on an open job owned by `module` and publish `etype`. The
   event is published only when the row read back still shows the state
   this update set; a concurrent later change publishes its own event."
  [{:keys [store bus]} module id set-clause params etype]
  (cond
    (not (and (string? id) (not (str/blank? id)))) (invalid ":id must be a non-blank string")
    :else
    (let [sql (str "UPDATE jobs SET " set-clause ", updated_at = ? WHERE id = ? AND " open-statuses
                   (when-not (= :hub module) " AND module = ?"))
          args (concat params [(now) id] (when-not (= :hub module) [(name module)]))
          reply (try (store/tx! store module [(into [sql] args)])
                     (catch clojure.lang.ExceptionInfo e
                       (if (= :invalid-job (:type (ex-data e))) {:ok false :error (ex-data e)} (throw e))))]
      (cond
        (not (:ok reply)) reply
        (= 1 (get-in reply [:result :rows-affected]))
        (let [job (get-job store id)]
          (when (= (event-status etype) (:status job)) (publish! bus etype job))
          {:ok true :result job})
        :else (explain-no-change store module id)))))

(defn create!
  "Insert a queued job of `kind` for `module`; reply {:ok true :result job}."
  [{:keys [store bus]} module {:keys [id kind input]}]
  (cond
    (not (keyword? module)) (invalid "module must be a keyword")
    (not (and (string? kind) (not (str/blank? kind)))) (invalid ":kind must be a non-blank string")
    (and (some? id) (not (and (string? id) (not (str/blank? id)) (<= (count id) 128)))) (invalid ":id must be a non-blank string of at most 128 characters")
    :else
    (let [id (or id (str (random-uuid)))
          t (now)
          reply (try (store/tx! store module
                               [["INSERT INTO jobs (id, module, kind, status, progress, input, created_at, updated_at) VALUES (?, ?, ?, 'queued', 0, ?, ?, ?)"
                                 id (name module) kind (edn-str input) t t]])
                     (catch clojure.lang.ExceptionInfo e
                       (if (= :invalid-job (:type (ex-data e))) {:ok false :error (ex-data e)} (throw e))))]
      (if (:ok reply)
        {:ok true :result (publish! bus :job/created (get-job store id))}
        reply))))

(defn progress!
  "Report progress (0..1) and an optional message; the job becomes running.
   On a cancelled job the reply is {:type :job-finished :status :cancelled}."
  [deps module {:keys [id progress message]}]
  (cond
    (not (and (number? progress) (<= 0 progress 1))) (invalid ":progress must be a number between 0 and 1")
    (and (some? message) (not (string? message))) (invalid ":message must be a string")
    :else (change! deps module id "status = 'running', progress = ?, message = ?" [(double progress) message] :job/progress)))

(defn complete!
  [deps module {:keys [id result]}]
  (change! deps module id "status = 'done', progress = 1, result = ?, finished_at = ?" [(edn-str result) (now)] :job/completed))

(defn fail!
  [deps module {:keys [id error]}]
  (change! deps module id "status = 'failed', error = ?, finished_at = ?" [(edn-str error) (now)] :job/failed))

(defn cancel!
  "Cancel an open job: it is finished at once (status cancelled,
   cancel_requested set); the module's next report is refused with
   :job-finished."
  [deps module {:keys [id]}]
  (change! deps module id "status = 'cancelled', cancel_requested = 1, finished_at = ?" [(now)] :job/cancelled))

(defn get!
  [{:keys [store]} module {:keys [id]}]
  (if-let [job (and (string? id) (get-job store id))]
    (if (owner? job module)
      {:ok true :result job}
      {:ok false :error {:type :not-owner :id id :owner (:module job)}})
    {:ok false :error {:type :not-found :id id}}))

(defn handle
  "Dispatch `op` for `module` (the ctx's :module/id); `deps` is {:store :bus}."
  [deps module op job]
  (case op
    :create (create! deps module job)
    :progress (progress! deps module job)
    :complete (complete! deps module job)
    :fail (fail! deps module job)
    :cancel (cancel! deps module job)
    :get (get! deps module job)
    {:ok false :error {:type :unknown-op :op op :ops (vec (sort ops))}}))
