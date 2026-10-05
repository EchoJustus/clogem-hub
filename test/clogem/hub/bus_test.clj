;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.bus-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.bus :as bus]
            [clogem.hub.log :as log]
            [clogem.sdk.event :as event]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- with-bus [opts f]
  (let [b (bus/new-bus opts)]
    (try (f b) (finally (bus/stop! b)))))

(defn- collector
  "Subscribe and collect events into an atom; returns [atom unsubscribe]."
  ([b etype] (collector b etype {}))
  ([b etype opts]
   (let [seen (atom [])]
     [seen (bus/subscribe! b etype #(swap! seen conj %) opts)])))

(defn- wait-for
  "Block until (pred) is truthy or `ms` elapsed; returns the final value."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 5)
            (recur))))))

(deftest events-reach-only-their-subscribers-in-order
  (with-bus {}
    (fn [b]
      (let [[said _] (collector b :echo/said)
            [other _] (collector b :echo/other)
            threads (atom #{})
            _ (bus/subscribe! b :echo/said (fn [_] (swap! threads conj (.getName (Thread/currentThread)))))
            events (mapv #(event/event :echo :echo/said {:n %}) (range 20))]
        (doseq [e events] (is (= e (bus/publish! b e))))
        (bus/publish! b (event/event :echo :echo/other {:x 1}))
        (is (wait-for #(= 20 (count @said)) 2000))
        (is (= (map (comp :n :event/payload) events) (map (comp :n :event/payload) @said)) "delivery keeps publish order")
        (is (every? event/valid? @said) "subscribers see the full envelope")
        (is (wait-for #(= 1 (count @other)) 2000))
        (is (every? #(not (.startsWith ^String % "async-dispatch")) @threads)
            "handlers run on async/thread threads (named async-mixed-N in this core.async), never on go-block dispatch threads")
        (let [s (bus/stats b)]
          (is (= 21 (:published s)))
          (is (= 41 (:delivered s)) "20 events × 2 subscribers + 1")
          (is (= 0 (:dropped s))))))))

(deftest unsubscribe-stops-delivery
  (with-bus {}
    (fn [b]
      (let [[seen unsub] (collector b :echo/said)]
        (bus/publish! b (event/event :echo :echo/said {:n 1}))
        (is (wait-for #(= 1 (count @seen)) 2000))
        (is (true? (unsub)))
        (is (nil? (unsub)) "second call is a no-op")
        (bus/publish! b (event/event :echo :echo/said {:n 2}))
        (Thread/sleep 50)
        (is (= 1 (count @seen)))
        (is (= 0 (:subscribers (bus/stats b))))))))

(deftest invalid-envelopes-are-rejected
  (with-bus {}
    (fn [b]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid event envelope"
                            (bus/publish! b {:event/type :echo/said})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid event envelope"
                            (bus/publish! b (assoc (event/event :echo :echo/said {}) :extra 1))))
      (is (thrown? clojure.lang.ExceptionInfo (bus/subscribe! b :unqualified identity)))
      (is (thrown? clojure.lang.ExceptionInfo (bus/subscribe! b :echo/said "not a fn")))
      (is (= 0 (:published (bus/stats b)))))))

(deftest a-throwing-handler-is-reported-and-the-bus-keeps-going
  (with-bus {}
    (fn [b]
      (let [[errors _] (collector b :bus/error)
            [seen _] (collector b :echo/said)
            calls (atom 0)]
        (bus/subscribe! b :echo/said (fn [e] (swap! calls inc) (when (odd? (:n (:event/payload e))) (throw (ex-info "boom" {})))))
        (doseq [n (range 4)] (bus/publish! b (event/event :echo :echo/said {:n n})))
        (is (wait-for #(= 4 (count @seen)) 2000))
        (is (wait-for #(= 2 (count @errors)) 2000) "one :bus/error per failing handler call")
        (is (= 4 @calls) "the failing subscriber keeps receiving")
        (let [p (:event/payload (first @errors))]
          (is (= :handler-failed (:reason p)))
          (is (= :echo/said (:event/type p)))
          (is (= "boom" (:message p)))
          (is (uuid? (:event/id p)))
          (is (= :hub (:event/source (first @errors)))))
        (is (= 2 (:errors (bus/stats b))))))))

(deftest a-slow-subscriber-overflows-alone
  (with-bus {}
    (fn [b]
      (let [gate (promise)
            slow (atom [])
            _ (bus/subscribe! b :echo/said (fn [e] @gate (swap! slow conj e)) {:buffer 2 :id :slow})
            [fast _] (collector b :echo/said)
            [errors _] (collector b :bus/error)]
        (doseq [n (range 10)] (bus/publish! b (event/event :echo :echo/said {:n n})))
        (is (wait-for #(= 10 (count @fast)) 2000) "the fast subscriber gets everything")
        (is (wait-for #(pos? (count @errors)) 2000))
        (is (every? #(= {:reason :subscriber-overflow :subscriber :slow}
                        (select-keys (:event/payload %) [:reason :subscriber]))
                    @errors))
        (is (= 10 (+ (count @errors) 3)) "buffer 2 plus the event in the handler; the other 7 are dropped")
        (deliver gate :open)
        (is (wait-for #(= 3 (count @slow)) 2000))
        (let [s (bus/stats b)]
          (is (= 7 (:dropped s)))
          (is (= 7 (:errors s))))))))

(deftest commands-have-one-owner-and-a-reply-channel
  (with-bus {}
    (fn [b]
      (let [threads (atom #{})
            unregister (bus/register-owner! b :math/add
                                            (fn [{:keys [a b]}]
                                              (swap! threads conj (.getName (Thread/currentThread)))
                                              {:ok true :result (+ a b)}))]
        (is (= {:ok true :result 3} (bus/request! b {:command :math/add :a 1 :b 2})))
        (is (= {:ok false :error {:type :unknown-command :command :math/sub}}
               (bus/request! b {:command :math/sub})))
        (is (= [:math/add] (bus/owners b)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already has an owner"
                              (bus/register-owner! b :math/add (fn [_] {:ok true}))))
        (is (every? #(not (.startsWith ^String % "async-dispatch")) @threads) "owner handlers run on their own thread")
        (testing "concurrent requests are all answered"
          (let [replies (->> (range 200)
                             (mapv (fn [i] (future (bus/request! b {:command :math/add :a i :b 1}))))
                             (mapv deref))]
            (is (= (map inc (range 200)) (map :result replies)))))
        (testing "a throwing owner yields a handler-failed reply and a :bus/error"
          (let [[errors _] (collector b :bus/error)]
            (bus/register-owner! b :math/fail (fn [_] (throw (ex-info "nope" {}))))
            (is (= {:ok false :error {:type :handler-failed :command :math/fail :message "nope"}}
                   (bus/request! b {:command :math/fail})))
            (is (wait-for #(= 1 (count @errors)) 2000))
            (is (= :math/fail (get-in (first @errors) [:event/payload :command])))))
        (testing "a non-map reply is reported as such"
          (bus/register-owner! b :math/odd (fn [_] 42))
          (is (= {:ok false :error {:type :bad-reply :command :math/odd}} (bus/request! b {:command :math/odd}))))
        (testing "timeouts"
          (bus/register-owner! b :math/slow (fn [_] (Thread/sleep 300) {:ok true}))
          (is (= {:ok false :error {:type :timeout :command :math/slow :phase :reply}}
                 (bus/request! b {:command :math/slow} {:timeout-ms 50})))
          (is (= 1 (:timeouts (bus/stats b)))))
        (testing "unregister frees the command"
          (is (true? (unregister)))
          (is (= {:ok false :error {:type :unknown-command :command :math/add}}
                 (bus/request! b {:command :math/add :a 1 :b 2}))))))))

(deftest stop-closes-everything
  (let [b (bus/new-bus {})
        [seen _] (collector b :echo/said)
        _ (bus/register-owner! b :x/y (fn [_] {:ok true}))]
    (bus/publish! b (event/event :echo :echo/said {}))
    (is (wait-for #(= 1 (count @seen)) 2000))
    (bus/stop! b)
    (is (not (bus/open? b)))
    (is (= 0 (:subscribers (bus/stats b))))
    (is (= 0 (:owners (bus/stats b))))
    (bus/publish! b (event/event :echo :echo/said {}))
    (is (= 1 (:dropped (bus/stats b))) "publishing on a stopped bus is counted, not thrown")
    (is (= {:ok false :error {:type :unknown-command :command :x/y}} (bus/request! b {:command :x/y})))))
