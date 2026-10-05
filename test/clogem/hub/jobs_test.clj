;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.jobs-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.bus :as bus]
            [clogem.hub.db.store :as store]
            [clogem.hub.jobs :as jobs]
            [clogem.hub.log :as log]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 5) (recur))))))

(defn- with-jobs [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-jobs"})
        b (bus/new-bus)
        s (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})
        events (atom [])]
    (doseq [t [:job/created :job/progress :job/completed :job/failed :job/cancelled]]
      (bus/subscribe! b t #(swap! events conj %)))
    (try (f {:store s :bus b} events)
         (finally (store/close! s) (bus/stop! b) (fs/delete-tree dir)))))

(deftest a-job-moves-through-its-lifecycle-and-publishes-each-step
  (with-jobs
    (fn [deps events]
      (let [{:keys [ok result]} (jobs/handle deps :echo :create {:kind "transcribe" :input {:media "m1" :lang "en"}})
            id (:id result)]
        (is (true? ok))
        (is (string? id))
        (is (= {:module :echo :kind "transcribe" :status :queued :progress 0.0 :message nil
                :input {:media "m1" :lang "en"} :result nil :error nil :cancel-requested? false :finished-at nil}
               (dissoc result :id :created-at :updated-at)))
        (is (pos? (:created-at result)))
        (testing "progress starts the job"
          (let [r (jobs/handle deps :echo :progress {:id id :progress 0.25 :message "decoding"})]
            (is (true? (:ok r)))
            (is (= [:running 0.25 "decoding" false] ((juxt :status :progress :message :cancel-requested?) (:result r))))))
        (testing "another module cannot touch it, the hub can read it"
          (is (= {:ok false :error {:type :not-owner :id id :owner :echo}}
                 (jobs/handle deps :other :progress {:id id :progress 0.5})))
          (is (= {:ok false :error {:type :not-owner :id id :owner :echo}} (jobs/handle deps :other :get {:id id})))
          (is (= :running (get-in (jobs/handle deps :hub :get {:id id}) [:result :status]))))
        (testing "completion is final"
          (let [r (jobs/handle deps :echo :complete {:id id :result {:segments 12}})]
            (is (= [:done 1.0 {:segments 12}] ((juxt :status :progress :result) (:result r))))
            (is (pos? (get-in r [:result :finished-at]))))
          (is (= {:ok false :error {:type :job-finished :id id :status :done}}
                 (jobs/handle deps :echo :progress {:id id :progress 0.9})))
          (is (= {:ok false :error {:type :job-finished :id id :status :done}}
                 (jobs/handle deps :echo :cancel {:id id}))))
        (testing "failure"
          (let [{:keys [result]} (jobs/handle deps :echo :create {:kind "chapter"})
                r (jobs/handle deps :echo :fail {:id (:id result) :error {:type :model-timeout :ms 30000}})]
            (is (= [:failed {:type :model-timeout :ms 30000}] ((juxt :status :error) (:result r))))))
        (testing "cancellation finishes the job and flags it for a module still reporting"
          (let [{:keys [result]} (jobs/handle deps :echo :create {:kind "chapter"})
                cid (:id result)]
            (jobs/handle deps :echo :progress {:id cid :progress 0.1})
            (let [r (jobs/handle deps :hub :cancel {:id cid})]
              (is (= [:cancelled true] ((juxt :status :cancel-requested?) (:result r)))) "the hub may cancel any module's job")
            (is (= {:ok false :error {:type :job-finished :id cid :status :cancelled}}
                   (jobs/handle deps :echo :progress {:id cid :progress 0.2})))))
        (testing "listing"
          (is (= ["chapter" "chapter" "transcribe"] (sort (map :kind (jobs/list-jobs (:store deps) {})))))
          (is (= 1 (count (jobs/list-jobs (:store deps) {:status :done}))))
          (is (= 3 (count (jobs/list-jobs (:store deps) {:module :echo}))))
          (is (= 0 (count (jobs/list-jobs (:store deps) {:module :other})))))
        (is (wait-for #(= 8 (count @events)) 3000))
        (is (= [:job/created :job/progress :job/completed :job/created :job/failed :job/created :job/progress :job/cancelled]
               (map :event/type @events)))
        (is (every? #(= :hub (:event/source %)) @events))
        (is (= id (get-in (first @events) [:event/payload :id])))))))

(deftest bad-requests-are-rejected-without-touching-the-database
  (with-jobs
    (fn [deps _]
      (let [problems (fn [r] (is (= :invalid-job (get-in r [:error :type])) (pr-str r)) (first (get-in r [:error :problems])))]
        (is (= ":kind must be a non-blank string" (problems (jobs/handle deps :echo :create {}))))
        (is (= ":kind must be a non-blank string" (problems (jobs/handle deps :echo :create {:kind " "}))))
        (is (= "module must be a keyword" (problems (jobs/handle deps "echo" :create {:kind "x"}))))
        (is (re-find #"128" (problems (jobs/handle deps :echo :create {:kind "x" :id (apply str (repeat 129 "a"))}))))
        (is (= ":progress must be a number between 0 and 1" (problems (jobs/handle deps :echo :progress {:id "j" :progress 1.5}))))
        (is (= ":message must be a string" (problems (jobs/handle deps :echo :progress {:id "j" :progress 0.5 :message 7}))))
        (is (= ":id must be a non-blank string" (problems (jobs/handle deps :echo :complete {:result 1})))))
      (is (= {:ok false :error {:type :not-found :id "nope"}} (jobs/handle deps :echo :progress {:id "nope" :progress 0.5})))
      (is (= {:ok false :error {:type :not-found :id "nope"}} (jobs/handle deps :echo :get {:id "nope"})))
      (is (= {:ok false :error {:type :not-found :id nil}} (jobs/handle deps :echo :get {})))
      (is (= {:ok false :error {:type :unknown-op :op :pause :ops [:cancel :complete :create :fail :get :progress]}}
             (jobs/handle deps :echo :pause {})))
      (is (empty? (jobs/list-jobs (:store deps) {})))
      (testing "a caller-chosen id is kept and must be unique"
        (is (= "my-job" (get-in (jobs/handle deps :echo :create {:kind "x" :id "my-job"}) [:result :id])))
        (let [r (jobs/handle deps :echo :create {:kind "x" :id "my-job"})]
          (is (= :sql-error (get-in r [:error :type])))
          (is (re-find #"UNIQUE" (get-in r [:error :message]))))))))
