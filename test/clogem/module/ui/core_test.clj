;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.ui.core-test
  "The built-in `ui` module against the SDK test kit: the fake runtime
   records every transaction, query, event and log line, and `deliver!`
   drives the module's `:ui/command` handler. Module test namespaces may
   require only the kit besides clojure.* and the module itself (the lint's
   module-isolation rule), so the same module through the real bus, store,
   runtime and registry is proven in clogem.hub.builtin.ui-test."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.module.ui.core :as core]
            [clogem.module.ui.inbox :as inbox]
            [clogem.module.ui.resources :as resources]
            [clogem.module.ui.tools :as tools]
            [clogem.sdk.testkit :as kit]))

(def manifest
  (edn/read-string (slurp (io/resource "clogem/module/ui/manifest.edn"))))

(def insert-sql "INSERT INTO ui_inbox (client, command, created_ms) VALUES (?, ?, ?)")
(def ack-sql "UPDATE ui_inbox SET acked_ms = ? WHERE id = ? AND client = ? AND acked_ms IS NULL")
(def exists-sql "SELECT id FROM ui_inbox WHERE id = ? AND client = ?")
(def list-sql "SELECT id, command, created_ms FROM ui_inbox WHERE client = ? AND acked_ms IS NULL ORDER BY id")

(defn- writer-reply
  "What the hub's writer answers for a transaction touching `n` rows."
  [n]
  {:ok true :result {:rows-affected n :last-inserted-id 7 :statements 1}})

(defn- started
  "A fake runtime built from `opts` with the module started on it: [rt ctx state]."
  [opts]
  (let [rt (kit/runtime opts)
        ctx (kit/ctx rt :ui manifest)
        state ((:start core/module) ctx)]
    [rt ctx state]))

(defn- updates [rt]
  (filterv #(= :resource/updated (:event/type %)) (kit/published rt)))

(deftest the-manifest-wires-this-module
  (is (= :ui (:module/id manifest)))
  (is (= 'clogem.module.ui.core/module (:module/entry manifest)))
  (is (= #{:ui/command} (get-in manifest [:bus :subscribes])))
  (is (contains? (get-in manifest [:bus :publishes]) :resource/updated))
  (is (= "clogem/module/ui/migrations" (get-in manifest [:db :migrations])))
  (is (= [["ui_ack" 'clogem.module.ui.tools/ack #{:gui :admin}]]
         (mapv (juxt :name :handler :profiles) (get-in manifest [:mcp :tools]))))
  (is (= [["clogem://ui/inbox/{client}" 'clogem.module.ui.resources/inbox "application/json" #{:gui :admin}]]
         (mapv (juxt :uri-template :handler :mime-type :profiles) (get-in manifest [:mcp :resource-templates]))))
  (is (fn? (:start core/module)))
  (is (fn? (:stop core/module)))
  (is (= {:status :ok} ((:health core/module) nil))))

(deftest start-subscribes-to-ui-command-and-stop-unsubscribes
  (let [[rt _ctx state] (started {})]
    (is (= [[:ui/command :ui]] (mapv (juxt :event/type :module/id) (kit/subscriptions rt))))
    (is (fn? (:unsubscribe state)))
    (is (integer? (:started-at state)))
    (is (= [{:level :info :event {:msg "ui module started"}}] (kit/logged rt)))
    (is (= {:status :ok} ((:health core/module) state)))
    (is (nil? ((:stop core/module) state)))
    (is (empty? (kit/subscriptions rt)) "stop unsubscribes")
    (is (nil? ((:stop core/module) nil)) "stopping without a state is harmless")))

(deftest a-command-is-stored-as-json-and-the-inbox-is-announced
  (let [[rt _ctx _state] (started {:tx-reply (fn [_tx] (writer-reply 1))})
        before (System/currentTimeMillis)
        command {:action "open" :media "m-1" :a 1.5 :b 3.5 :tags ["x" "y"] :nested {:k :v}}]
    (kit/deliver! rt :ui/command {:client "gui" :command command} :player)
    (let [[[sql client text created-ms :as stmt] & more] (first (kit/txs rt))]
      (is (= 1 (count (kit/txs rt))) "one transaction")
      (is (nil? more) "with one statement")
      (is (= 4 (count stmt)) "three parameters for three placeholders")
      (is (= insert-sql sql))
      (is (= "gui" client))
      (is (= {"action" "open" "media" "m-1" "a" 1.5 "b" 3.5 "tags" ["x" "y"] "nested" {"k" "v"}}
             (json/parse-string text))
          "the command is stored as JSON text")
      (is (and (integer? created-ms) (<= before created-ms (System/currentTimeMillis)))
          "created_ms is integer milliseconds"))
    (is (= [[:resource/updated :ui {:uri "clogem://ui/inbox/gui"}]]
           (mapv (juxt :event/type :event/source :event/payload) (kit/published rt))))
    (is (empty? (kit/queries rt)) "storing a command reads nothing")))

(deftest invalid-payloads-are-logged-and-dropped
  (let [[rt ctx _state] (started {:tx-reply (fn [_tx] (writer-reply 1))})]
    (doseq [payload [{} {:client 5 :command {}} {:client "gui"} {:command {:x 1}}
                     {:client "gui" :command "nope"} {:client "gui" :command [1 2]}
                     {:client "" :command {}} {:client "bad client" :command {}}
                     {:client "a/b" :command {}} {:client (apply str (repeat 65 "a")) :command {}}]]
      (kit/deliver! rt :ui/command payload :player)
      (is (nil? (inbox/receive! ctx payload)) (pr-str payload)))
    (is (empty? (kit/txs rt)) "nothing is written")
    (is (empty? (kit/published rt)) "nothing is announced")
    (let [dropped (filter #(= "ui command dropped" (get-in % [:event :msg])) (kit/logged rt))]
      (is (= 20 (count dropped)) "every bad payload is logged, through deliver! and directly")
      (is (every? #(= [:warn :invalid-payload] [(:level %) (get-in % [:event :reason])]) dropped))
      (is (every? #(some? (get-in % [:event :problems])) dropped)))
    (testing "a payload wider than the contract is accepted: the inbox ignores extra keys"
      (is (= 7 (inbox/receive! ctx {:client "gui" :command {:x 1} :trace "t"})))
      (is (= 1 (count (kit/txs rt)))))))

(deftest commands-the-store-cannot-take-are-logged-and-dropped
  (testing "a command that is not JSON data"
    (let [[rt ctx _state] (started {:tx-reply (fn [_tx] (writer-reply 1))})]
      (is (nil? (inbox/receive! ctx {:client "gui" :command {:f (fn [] 1)}})))
      (is (empty? (kit/txs rt)))
      (is (empty? (kit/published rt)))
      (let [{:keys [level event]} (last (kit/logged rt))] ; after the start line
        (is (= [:error "ui command dropped" :store-failed "gui"] [level (:msg event) (:reason event) (:client event)]))
        (is (nil? (:command event)) "the command itself is never logged"))))
  (testing "a transaction the writer refuses"
    (let [[rt ctx _state] (started {:tx-reply (fn [_tx] {:ok false :error {:type :sql-error :message "no such table"}})})]
      (is (nil? (inbox/receive! ctx {:client "gui" :command {:x 1}})) "the handler does not throw")
      (is (= 1 (count (kit/txs rt))))
      (is (empty? (kit/published rt)) "a refused command is not announced")
      (let [{:keys [level event]} (last (kit/logged rt))] ; after the start line
        (is (= [:error :store-failed :ui/tx-failed] [level (:reason event) (:type event)]))
        (is (re-find #"sql-error" (:error event))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storing a ui command failed"
                            (inbox/store! ctx "gui" {:x 1}))
          "store! itself reports the refusal to its caller"))))

(deftest ack-marks-the-row-and-announces-the-inbox
  (testing "an unread command"
    (let [rt (kit/runtime {:tx-reply (fn [_tx] (writer-reply 1))})
          ctx (kit/ctx rt :ui manifest)
          before (System/currentTimeMillis)]
      (is (= {:acked true} (tools/ack ctx {:client "gui" :id 7})))
      (let [[[sql acked-ms id client]] (first (kit/txs rt))]
        (is (= ack-sql sql))
        (is (<= before acked-ms (System/currentTimeMillis)))
        (is (= [7 "gui"] [id client])))
      (is (= [{:uri "clogem://ui/inbox/gui"}] (mapv :event/payload (updates rt))))
      (is (empty? (kit/queries rt)) "no lookup when the update took")))
  (testing "an already acknowledged command: idempotent, nothing announced"
    (let [rt (kit/runtime {:tx-reply (fn [_tx] (writer-reply 0))
                           :queries {[exists-sql 7 "gui"] [{:id 7}]}})
          ctx (kit/ctx rt :ui manifest)]
      (is (= {:acked true} (tools/ack ctx {:client "gui" :id 7})))
      (is (= [[exists-sql 7 "gui"]] (kit/queries rt)))
      (is (empty? (kit/published rt)))))
  (testing "no such row for that client"
    (let [rt (kit/runtime {:tx-reply (fn [_tx] (writer-reply 0))})
          ctx (kit/ctx rt :ui manifest)]
      (is (= {:acked false} (tools/ack ctx {:client "gui" :id 99})))
      (is (= {:acked false} (tools/ack ctx {:client "other" :id 7})))
      (is (empty? (kit/published rt)))))
  (testing "a refused transaction surfaces as an error"
    (let [rt (kit/runtime {:tx-reply (fn [_tx] {:ok false :error {:type :busy}})})
          ctx (kit/ctx rt :ui manifest)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"acknowledging a ui command failed"
                            (tools/ack ctx {:client "gui" :id 7})))
      (is (empty? (kit/published rt))))))

(deftest the-inbox-resource-lists-unacked-commands-oldest-first-as-json
  (let [rows [{:id 1 :command "{\"action\":\"open\",\"media\":\"m-1\"}" :created_ms 10}
              {:id 2 :command "{\"action\":\"seek\",\"t\":2.5}" :created_ms 20}
              {:id 3 :command "not json" :created_ms 30}]
        rt (kit/runtime {:queries (fn [q] (when (= q [list-sql "gui"]) rows))})
        ctx (kit/ctx rt :ui manifest)
        {:keys [mime-type text]} (resources/inbox ctx {:uri "clogem://ui/inbox/gui" :params {:client "gui"}})]
    (is (= "application/json" mime-type))
    (is (= [{"id" 1 "command" {"action" "open" "media" "m-1"} "created_ms" 10}
            {"id" 2 "command" {"action" "seek" "t" 2.5} "created_ms" 20}
            {"id" 3 "command" "not json" "created_ms" 30}]
           (json/parse-string text))
        "commands come back as JSON data; text that is not JSON is passed through")
    (is (str/starts-with? text "[{\"id\":1,\"command\":{") "id, command, created_ms in that order")
    (testing "an unknown client, a missing or malformed client parameter: an empty array, never nil"
      (is (= {:mime-type "application/json" :text "[]"}
             (resources/inbox ctx {:uri "clogem://ui/inbox/nobody" :params {:client "nobody"}})))
      (is (= "[]" (:text (resources/inbox ctx {:uri "clogem://ui/inbox/" :params {}}))))
      (is (= "[]" (:text (resources/inbox ctx {:uri "clogem://ui/inbox/x" :params {:client "bad client"}}))))
      (is (= [[list-sql "gui"] [list-sql "nobody"]] (kit/queries rt))
          "only client ids reach the database"))))

(deftest uri-and-client-rules
  (is (= "clogem://ui/inbox/gui" (inbox/uri "gui")))
  (is (every? inbox/client? ["gui" "llm" "admin" "stdio" "a" "9" "a.b-c_d" (apply str (repeat 64 "a"))]))
  (is (not-any? inbox/client? [nil "" " " "-a" "a b" "a/b" "a:b" (apply str (repeat 65 "a")) :gui 5])))
