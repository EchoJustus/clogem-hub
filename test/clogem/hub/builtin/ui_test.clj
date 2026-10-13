;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.builtin.ui-test
  "The built-in `ui` module through the hub itself: a real bus, a store on
   a temporary database, the hub runtime and the registry, whose
   before-start hook runs the module's migrations as the daemon does. A
   hand-built `:ui/command` envelope from the player stands in for the
   publishing module. (The module's own namespace tests live in
   clogem.module.ui.core-test, against the SDK test kit.)"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.bus :as bus]
            [clogem.hub.config :as config]
            [clogem.hub.db.store :as store]
            [clogem.hub.log :as log]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.hub.test-support :as support]
            [clogem.sdk.event :as event]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def ui-manifest (manifest/read-manifest (io/resource "clogem/module/ui/manifest.edn")))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 5) (recur))))))

(defn- migrate-module!
  "What clogem.hub.daemon/migrate-module! does before a module starts."
  [st m]
  (when-let [resource (get-in m [:db :migrations])]
    (store/migrate! st (:module/id m) resource)))

(defn- with-hub
  "(f {:bus :store :registry :updates}) over a bus, a store in a temporary
   directory, a runtime over both and a registry migrating modules before
   they start; :resource/updated events seen on the bus collect in :updates."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-ui"})
        b (bus/new-bus)
        st (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})
        rt (runtime/new-runtime {:config config/defaults :bus b :store st})
        reg (registry/new-registry {:runtime rt :bus b :before-start (partial migrate-module! st)})
        updates (atom [])]
    (runtime/attach-registry! rt reg)
    (bus/subscribe! b :resource/updated #(swap! updates conj %))
    (try (f {:bus b :store st :registry reg :updates updates})
         (finally
           (registry/stop-all! reg)
           (store/close! st)
           (bus/stop! b)
           (support/delete-tree! dir)))))

(defn- send-command!
  "Publish :ui/command as the player module would: a hand-built envelope."
  [b client command]
  (bus/publish! b (event/event :player :ui/command {:client client :command command})))

(defn- rows [st client]
  (store/query st ["SELECT id, client, command, created_ms, acked_ms FROM ui_inbox WHERE client = ? ORDER BY id" client]))

(defn- inbox-template [reg profile]
  (first (filter #(= "clogem://ui/inbox/{client}" (get-in % [:template :uri-template]))
                 (:templates (registry/resource-index reg profile)))))

(defn- read-inbox
  "The inbox resource of `client` as the MCP layer would call it, parsed."
  [reg client]
  (let [{:keys [handler ctx]} (inbox-template reg :gui)
        {:keys [mime-type text]} (handler ctx {:uri (str "clogem://ui/inbox/" client) :params {:client client}})]
    (is (= "application/json" mime-type))
    (is (string? text) "never nil: an empty inbox is a resource too")
    (json/parse-string text)))

(defn- ack! [reg args]
  (let [{:keys [handler ctx]} (registry/find-tool reg :gui "ui_ack")]
    (handler ctx args)))

(defn- update-uris [updates] (mapv (comp :uri :event/payload) @updates))

(deftest the-manifest-is-valid-and-the-module-registers-ready
  (is (= {:ok? true :problems []} (manifest/check ui-manifest)))
  (with-hub
    (fn [{:keys [bus store registry]}]
      (let [subscribers-before (:subscribers (bus/stats bus))
            entry (registry/register! registry ui-manifest)]
        (is (= :ready (:status entry)) (pr-str (:reason entry)))
        (is (= [{:module "ui" :version 1 :name "0001-ui.sql"}]
               (store/query store "SELECT module, version, name FROM schema_migrations WHERE module = 'ui'"))
            "the migration ran before the start")
        (is (= [{:name "ui_inbox"}] (store/query store "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'ui_inbox'")))
        (is (= [{:name "ui_inbox_client_acked"}] (store/query store "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'ui_inbox_client_acked'")))
        (is (= (inc subscribers-before) (:subscribers (bus/stats bus))) "start subscribed to :ui/command")
        (testing "projections: the gui and admin profiles only"
          (is (= ["ui_ack"] (map (comp :name :tool) (registry/tools-for registry :gui))))
          (is (= ["ui_ack"] (map (comp :name :tool) (registry/tools-for registry :admin))))
          (is (empty? (registry/tools-for registry :llm-small)))
          (is (empty? (registry/tools-for registry :llm-large)))
          (is (some? (inbox-template registry :gui)))
          (is (some? (inbox-template registry :admin)))
          (is (nil? (inbox-template registry :llm-large)))
          (is (= {:resources {} :templates []} (registry/resource-index registry :llm-small))))
        (testing "stop unsubscribes; a second registration applies no migration twice"
          (is (true? (registry/unregister! registry :ui)))
          (is (= subscribers-before (:subscribers (bus/stats bus))))
          (is (= :ready (:status (registry/register! registry ui-manifest))))
          (is (= 1 (-> (store/query store "SELECT count(*) AS n FROM schema_migrations WHERE module = 'ui'") first :n))))))))

(deftest commands-are-stored-announced-read-and-acknowledged
  (with-hub
    (fn [{:keys [bus store registry updates]}]
      (is (= :ready (:status (registry/register! registry ui-manifest))))
      (let [before (System/currentTimeMillis)
            command {:action "open" :media "m-1" :a 1.5 :b 3.5}]
        (send-command! bus "gui" command)
        (is (wait-for #(= 1 (count (rows store "gui"))) 5000) "the row appears")
        (let [[row] (rows store "gui")]
          (is (= {:id 1 :client "gui" :acked_ms nil} (select-keys row [:id :client :acked_ms])))
          (is (= {"action" "open" "media" "m-1" "a" 1.5 "b" 3.5} (json/parse-string (:command row)))
              "the command is stored as JSON text")
          (is (<= before (:created_ms row) (System/currentTimeMillis))))
        (is (wait-for #(= 1 (count @updates)) 5000) "the inbox is announced")
        (is (= [[:resource/updated :ui {:uri "clogem://ui/inbox/gui"}]]
               (mapv (juxt :event/type :event/source :event/payload) @updates)))
        (is (= [{"id" 1 "command" {"action" "open" "media" "m-1" "a" 1.5 "b" 3.5} "created_ms" (:created_ms (first (rows store "gui")))}]
               (read-inbox registry "gui")))
        (testing "a second command for the same client, one for another: oldest first, per client"
          (send-command! bus "gui" {:action "seek" :t 2.5})
          (send-command! bus "admin" {:action "ping"})
          (is (wait-for #(and (= 2 (count (rows store "gui"))) (= 1 (count (rows store "admin")))) 5000))
          (is (wait-for #(= 3 (count @updates)) 5000))
          (is (= [1 2] (mapv #(get % "id") (read-inbox registry "gui"))))
          (is (= [{"action" "open" "media" "m-1" "a" 1.5 "b" 3.5} {"action" "seek" "t" 2.5}]
                 (mapv #(get % "command") (read-inbox registry "gui"))))
          (is (= [{"action" "ping"}] (mapv #(get % "command") (read-inbox registry "admin"))))
          (is (= ["clogem://ui/inbox/gui" "clogem://ui/inbox/gui" "clogem://ui/inbox/admin"] (update-uris updates))))
        (testing "ack removes the command from the inbox and announces it"
          (is (= {:acked true} (ack! registry {:client "gui" :id 1})))
          (let [[row] (rows store "gui")]
            (is (<= before (:acked_ms row) (System/currentTimeMillis))))
          (is (= [2] (mapv #(get % "id") (read-inbox registry "gui"))))
          (is (wait-for #(= 4 (count @updates)) 5000))
          (is (= "clogem://ui/inbox/gui" (last (update-uris updates)))))
        (testing "acknowledging again is idempotent: same reply, same time, nothing announced"
          (let [acked-ms (:acked_ms (first (rows store "gui")))]
            (is (= {:acked true} (ack! registry {:client "gui" :id 1})))
            (is (= acked-ms (:acked_ms (first (rows store "gui")))))
            (Thread/sleep 50)
            (is (= 4 (count @updates)))))
        (testing "an unknown id, or another client's id: false, nothing changes"
          (is (= {:acked false} (ack! registry {:client "gui" :id 99})))
          (is (= {:acked false} (ack! registry {:client "other" :id 2})))
          (is (nil? (:acked_ms (second (rows store "gui")))) "row 2 is still unread")
          (is (= [2] (mapv #(get % "id") (read-inbox registry "gui"))))
          (Thread/sleep 50)
          (is (= 4 (count @updates))))
        (testing "an unknown client reads as an empty array"
          (is (= [] (read-inbox registry "nobody")))
          (is (= [] (read-inbox registry "bad client"))))))))

(deftest invalid-payloads-are-dropped-and-the-module-keeps-serving
  (with-hub
    (fn [{:keys [bus store registry updates]}]
      (is (= :ready (:status (registry/register! registry ui-manifest))))
      (doseq [payload [{} {:client 5 :command {}} {:client "gui"} {:command {:x 1}}
                       {:client "gui" :command "nope"} {:client "bad client" :command {}}]]
        (bus/publish! bus (event/event :player :ui/command payload)))
      ;; the subscriber handles its channel in order: when the valid command
      ;; has landed, every invalid one before it has been dropped
      (send-command! bus "gui" {:action "open"})
      (is (wait-for #(= 1 (count (rows store "gui"))) 5000))
      (is (= 1 (-> (store/query store "SELECT count(*) AS n FROM ui_inbox") first :n)) "nothing else was stored")
      (is (wait-for #(= 1 (count @updates)) 5000))
      (Thread/sleep 50)
      (is (= ["clogem://ui/inbox/gui"] (update-uris updates)) "only the valid command was announced")
      (is (= [{"action" "open"}] (mapv #(get % "command") (read-inbox registry "gui"))))
      (is (= 0 (:errors (bus/stats bus))) "dropping is not a bus error: the handler never threw"))))
