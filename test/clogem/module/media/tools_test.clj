;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.tools-test
  "media_register, media_probe and the item resource against the SDK test
   kit: canned rows answer the module's two SELECTs, a fake ffprobe answers
   run-process!, and the statements, events and process runs the module
   makes are asserted. The same behaviour against a real store, bus and
   registry is proven by the hub's own test of this module
   (clogem.hub.module.media-test); module test namespaces may not require
   hub code."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clogem.module.media.fingerprint :as fingerprint]
            [clogem.module.media.golden :as golden]
            [clogem.module.media.resources :as resources]
            [clogem.module.media.tools :as tools]
            [clogem.sdk.testkit :as testkit]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- ex-data-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- answer-rows
  "A :queries fn answering the module's SELECTs from canned `rows`."
  [rows]
  (fn [[sql & params]]
    (let [[k] params]
      (filterv (fn [row]
                 (cond
                   (= sql tools/select-by-id) (= k (:id row))
                   (= sql tools/select-by-path) (= k (:path row))
                   :else false))
               rows))))

(defn- fake-ffprobe
  "Answers `ffprobe -version` with exit 0 and a probe with the golden document."
  [argv _opts]
  (if (= "-version" (second argv))
    {:out "ffprobe version 7.1\n"}
    {:out golden/ffprobe-json}))

(defn- kit [{:keys [rows process tx-reply]}]
  (testkit/runtime (cond-> {:queries (answer-rows (or rows []))
                            :process (or process fake-ffprobe)}
                     tx-reply (assoc :tx-reply tx-reply))))

(defn- ctx [rt] (testkit/ctx rt :media))

(defn- with-file
  "`f` with a temp directory and the path of an 8-byte file in it."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-media"})
        p (str (fs/path dir "clip.mp4"))]
    (fs/write-bytes p (.getBytes "clip-one" "UTF-8"))
    (try (f (str dir) p)
         (finally (fs/delete-tree dir)))))

(defn- row-for
  "The row media_register would have written for the file at `p`."
  [p]
  (let [fp (fingerprint/fingerprint p)]
    {:id (fingerprint/media-id fp golden/duration-ms) :path p :size (fs/size p) :fingerprint fp
     :duration_ms golden/duration-ms :probe nil :created_ms 1 :updated_ms 1}))

(defn- stored-probe-json
  "A stored probe document whose format_name tells it apart from the fake ffprobe's."
  []
  (json/generate-string {:format {:format_name "stored-format" :duration "95.400000"}
                         :streams [{:codec_type "video"}]
                         :duration_ms golden/duration-ms}))

(defn- probe-argv [p] (vec (concat ["ffprobe" "-v" "error" "-print_format" "json" "-show_format" "-show_streams" "-show_error"] [p])))

(defn- events [rt] (mapv (juxt :event/type :event/payload) (testkit/published rt)))

;; ---------------------------------------------------------------------------
;; media_register

(deftest register-refuses-a-missing-or-non-regular-path
  (with-file
    (fn [dir _]
      (let [rt (kit {})]
        (doseq [bad [(str (fs/path dir "nope.mp4")) dir "" "   "]]
          (let [data (ex-data-of #(tools/register (ctx rt) {:path bad}))]
            (is (= :media/not-found (:type data)) (pr-str bad))))
        (is (empty? (testkit/processes rt)) "ffprobe never ran")
        (is (empty? (testkit/txs rt)))
        (is (empty? (testkit/queries rt)))))))

(deftest register-inserts-new-content-and-announces-it
  (with-file
    (fn [_ p]
      (let [rt (kit {})
            r (tools/register (ctx rt) {:path p})
            fp (fingerprint/fingerprint p)
            id (fingerprint/media-id fp golden/duration-ms)]
        (is (= {:id id :path p :size 8 :duration-ms golden/duration-ms :fingerprint fp :created true :relinked false} r))
        (is (re-matches #"m-[0-9a-f]{32}" id))
        (testing "one ffprobe run with the documented argv"
          (is (= [(probe-argv p)] (map :argv (testkit/processes rt)))))
        (testing "the row was looked up by id and by path"
          (is (= [[tools/select-by-id id] [tools/select-by-path p]] (testkit/queries rt))))
        (testing "one transaction holding one INSERT"
          (let [txs (testkit/txs rt)
                [[sql & params]] (first txs)]
            (is (= 1 (count txs)))
            (is (= 1 (count (first txs))))
            (is (= tools/insert-item sql))
            (is (= [id p 8 fp golden/duration-ms] (take 5 params)))
            (let [stored (json/parse-string (nth params 5) true)]
              (is (= golden/duration-ms (:duration_ms stored)))
              (is (= 2 (count (:streams stored))))
              (is (= "mov,mp4,m4a,3gp,3g2,mj2" (get-in stored [:format :format_name]))))
            (is (every? integer? (drop 6 params)) "created_ms and updated_ms")
            (is (= (nth params 6) (nth params 7)))))
        (testing ":media/registered then :resource/updated, from :media"
          (is (= [[:media/registered {:id id :path p}]
                  [:resource/updated {:uri (str "clogem://media/item/" id)}]]
                 (events rt)))
          (is (every? #(= :media (:event/source %)) (testkit/published rt))))))))

(deftest a-file-without-a-duration-stores-0-in-the-row-and-in-the-document
  (with-file
    (fn [_ p]
      (let [rt (kit {:process (fn [_ _] {:out "{\"format\":{},\"streams\":[]}"})})
            r (tools/register (ctx rt) {:path p})
            [[sql & params]] (first (testkit/txs rt))]
        (is (= 0 (:duration-ms r)))
        (is (= (fingerprint/media-id (fingerprint/fingerprint p) 0) (:id r)) "the id is derived from 0 ms")
        (is (= tools/insert-item sql))
        (is (= 0 (nth params 4)) "the duration_ms column")
        (is (= 0 (:duration_ms (json/parse-string (nth params 5) true))) "the stored document carries the same value")))))

(deftest registering-the-same-file-again-changes-nothing
  (with-file
    (fn [_ p]
      (let [row (row-for p)
            rt (kit {:rows [row]})
            r (tools/register (ctx rt) {:path p})]
        (is (= {:id (:id row) :path p :size 8 :duration-ms golden/duration-ms :fingerprint (:fingerprint row)
                :created false :relinked false}
               r))
        (is (empty? (testkit/txs rt)) "no write")
        (is (empty? (testkit/published rt)) "no event")
        (is (= 1 (count (testkit/processes rt))) "ffprobe ran once, for the duration the id needs")))))

(deftest a-moved-file-is-relinked
  (with-file
    (fn [dir p]
      (let [old (str (fs/path dir "old-place.mp4"))
            row (assoc (row-for p) :path old)
            rt (kit {:rows [row]})
            r (tools/register (ctx rt) {:path p})
            id (:id row)]
        (is (= [id p true false] ((juxt :id :path :relinked :created) r)))
        (let [[[sql & params] :as tx] (first (testkit/txs rt))]
          (is (= 1 (count tx)))
          (is (= tools/relink-item sql))
          (is (= [p id] [(first params) (last params)]))
          (is (integer? (second params))))
        (is (= [[:media/registered {:id id :path p}]
                [:resource/updated {:uri (str "clogem://media/item/" id)}]]
               (events rt)))))))

(deftest a-row-without-a-path-is-relinked-too
  (with-file
    (fn [_ p]
      (let [row (assoc (row-for p) :path nil)
            rt (kit {:rows [row]})
            r (tools/register (ctx rt) {:path p})]
        (is (= [true false] ((juxt :relinked :created) r)))
        (is (= tools/relink-item (ffirst (first (testkit/txs rt)))))))))

(deftest changed-content-at-a-known-path-displaces-the-old-row
  (with-file
    (fn [_ p]
      (let [old {:id "m-00000000000000000000000000000000" :path p :size 3 :fingerprint "old"
                 :duration_ms 1000 :probe nil :created_ms 1 :updated_ms 1}
            rt (kit {:rows [old]})
            r (tools/register (ctx rt) {:path p})
            id (:id r)]
        (is (true? (:created r)))
        (is (false? (:relinked r)))
        (is (not= (:id old) id))
        (testing "one transaction: clear the old row's path, then insert"
          (let [tx (first (testkit/txs rt))]
            (is (= 1 (count (testkit/txs rt))))
            (is (= [tools/clear-path tools/insert-item] (map first tx)))
            (is (= (:id old) (last (first tx))))
            (is (= [id p] (take 2 (rest (second tx)))))))
        (testing "both items changed"
          (is (= [[:media/registered {:id id :path p}]
                  [:resource/updated {:uri (str "clogem://media/item/" id)}]
                  [:resource/updated {:uri (str "clogem://media/item/" (:id old))}]]
                 (events rt))))))))

(deftest known-content-at-a-path-held-by-other-content
  (with-file
    (fn [dir p]
      (let [mine (assoc (row-for p) :path (str (fs/path dir "elsewhere.mp4")))
            other {:id "m-11111111111111111111111111111111" :path p :size 3 :fingerprint "other"
                   :duration_ms 1000 :probe nil :created_ms 1 :updated_ms 1}
            rt (kit {:rows [mine other]})
            r (tools/register (ctx rt) {:path p})]
        (is (= [(:id mine) true false] ((juxt :id :relinked :created) r)))
        (let [tx (first (testkit/txs rt))]
          (is (= [tools/clear-path tools/relink-item] (map first tx)))
          (is (= (:id other) (last (first tx))))
          (is (= [p] (take 1 (rest (second tx)))))
          (is (= (:id mine) (last (second tx)))))
        (is (= [:media/registered :resource/updated :resource/updated] (map first (events rt))))))))

(deftest a-failed-transaction-is-an-error-and-publishes-nothing
  (with-file
    (fn [_ p]
      (let [rt (kit {:tx-reply {:ok false :error {:type :sql-error :message "UNIQUE constraint failed: media_items.path"}}})
            e (try (tools/register (ctx rt) {:path p}) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :media/db-error (:type (ex-data e))))
        (is (= :sql-error (get-in (ex-data e) [:error :type])))
        (is (re-find #"UNIQUE" (ex-message e)))
        (is (empty? (testkit/published rt)))))))

(deftest a-missing-ffprobe-propagates-as-the-api-error
  (with-file
    (fn [_ p]
      (let [rt (kit {:process (fn [_ _] (throw (ex-info "ffprobe is not installed" {:type :process/binary-missing :binary "ffprobe"})))})
            data (ex-data-of #(tools/register (ctx rt) {:path p}))]
        (is (= :process/binary-missing (:type data)))
        (is (empty? (testkit/txs rt)))))))

;; ---------------------------------------------------------------------------
;; media_probe

(deftest probe-by-id-uses-the-stored-document-while-the-file-is-unchanged
  (with-file
    (fn [_ p]
      (let [row (assoc (row-for p) :probe (stored-probe-json))
            rt (kit {:rows [row]})
            r (tools/probe (ctx rt) {:id (:id row)})]
        (is (= "stored-format" (get-in r [:format :format_name])) "answered from the row")
        (is (= [{:codec_type "video"}] (:streams r)))
        (is (= golden/duration-ms (:duration-ms r)))
        (is (empty? (testkit/processes rt)) "ffprobe did not run")
        (testing "once the file changed, ffprobe runs again"
          (fs/write-bytes p (.getBytes "clip-two" "UTF-8"))
          (let [r (tools/probe (ctx rt) {:id (:id row)})]
            (is (= "mov,mp4,m4a,3gp,3g2,mj2" (get-in r [:format :format_name])))
            (is (= [(probe-argv p)] (map :argv (testkit/processes rt))))))))))

(deftest probe-by-id-without-a-stored-document-runs-ffprobe
  (with-file
    (fn [_ p]
      (let [row (row-for p)
            rt (kit {:rows [row]})
            r (tools/probe (ctx rt) {:id (:id row)})]
        (is (= golden/duration-ms (:duration-ms r)))
        (is (= 2 (count (:streams r))))
        (is (= 1 (count (testkit/processes rt))))))))

(deftest probe-by-path
  (with-file
    (fn [dir p]
      (testing "an unregistered file is probed"
        (let [rt (kit {})
              r (tools/probe (ctx rt) {:path p})]
          (is (= golden/duration-ms (:duration-ms r)))
          (is (map? (:format r)))
          (is (= [(probe-argv p)] (map :argv (testkit/processes rt))))))
      (testing "a non-normalized path is normalized before the lookup and the probe"
        (let [rt (kit {})]
          (tools/probe (ctx rt) {:path (str (fs/path dir "sub" ".." "clip.mp4"))})
          (is (= [[tools/select-by-path p]] (testkit/queries rt)))
          (is (= [(probe-argv p)] (map :argv (testkit/processes rt))))))
      (testing "a registered, unchanged file answers from its row"
        (let [rt (kit {:rows [(assoc (row-for p) :probe (stored-probe-json))]})
              r (tools/probe (ctx rt) {:path p})]
          (is (= "stored-format" (get-in r [:format :format_name])))
          (is (empty? (testkit/processes rt)))))
      (testing "a missing file"
        (let [rt (kit {})]
          (is (= :media/not-found (:type (ex-data-of #(tools/probe (ctx rt) {:path (str (fs/path dir "nope.mp4"))}))))))))))

(deftest probe-argument-errors
  (with-file
    (fn [_ p]
      (let [row (row-for p)
            rt (kit {:rows [row (assoc row :id "m-22222222222222222222222222222222" :path nil)]})]
        (is (= :media/invalid-arguments (:type (ex-data-of #(tools/probe (ctx rt) {})))))
        (is (= :media/invalid-arguments (:type (ex-data-of #(tools/probe (ctx rt) {:id (:id row) :path p})))))
        (is (= :media/not-found (:type (ex-data-of #(tools/probe (ctx rt) {:id "m-nope"})))))
        (let [data (ex-data-of #(tools/probe (ctx rt) {:id "m-22222222222222222222222222222222"}))]
          (is (= :media/not-found (:type data)) "a row whose file was replaced has no path to probe")
          (is (nil? (:path data))))
        (is (empty? (testkit/processes rt)))))))

;; ---------------------------------------------------------------------------
;; clogem://media/item/{id}

(deftest the-item-resource-returns-the-row-as-json-or-nil
  (with-file
    (fn [_ p]
      (let [row (assoc (row-for p) :probe golden/ffprobe-json :created_ms 100 :updated_ms 200)
            rt (kit {:rows [row]})
            uri (str "clogem://media/item/" (:id row))
            r (resources/item (ctx rt) {:uri uri :params {:id (:id row)}})]
        (is (= "application/json" (:mime-type r)))
        (let [doc (json/parse-string (:text r) true)]
          (is (= {:id (:id row) :path p :size 8 :fingerprint (:fingerprint row) :duration_ms golden/duration-ms
                  :created_ms 100 :updated_ms 200}
                 (dissoc doc :probe)))
          (is (= "95.400000" (get-in doc [:probe :format :duration])) "the stored document, parsed, not a nested string"))
        (is (nil? (resources/item (ctx rt) {:uri "clogem://media/item/m-nope" :params {:id "m-nope"}})))
        (let [queries-before (count (testkit/queries rt))]
          (is (nil? (resources/item (ctx rt) {:uri "clogem://media/item/" :params {}})))
          (is (= queries-before (count (testkit/queries rt))) "a missing template variable runs no SELECT"))))))
