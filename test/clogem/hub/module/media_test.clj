;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.module.media-test
  "The built-in `media` module through the real hub: a bus, a store on a
   temporary database, a runtime whose :process fakes ffprobe, a registry
   that runs the module's migration before starting it, the tool handlers
   taken from registry/tools-for and the resource handler from
   registry/resource-index. This lives under clogem.hub because module test
   namespaces (clogem.module.media.*) may not require hub code (lint rule
   :module-isolation); the module's own tests use the SDK test kit."
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
            [clogem.module.media.golden :as golden]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def media-manifest (manifest/read-manifest (io/resource "clogem/module/media/manifest.edn")))

(defn- wait-for [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (pred) (when (< (System/currentTimeMillis) deadline) (Thread/sleep 5) (recur))))))

(defn- fake-ffprobe
  "The runtime's :process fn: records every argv; answers `ffprobe -version`
   with exit 0, a probe of an existing file with the golden document and a
   probe of a missing file the way ffprobe does (error document, exit 1)."
  [argvs]
  (fn [_ctx argv _opts]
    (swap! argvs conj argv)
    (cond
      (= ["ffprobe" "-version"] argv) (golden/process-reply argv {:out "ffprobe version 7.1\n"})
      (and (= "ffprobe" (first argv)) (fs/regular-file? (last argv))) (golden/process-reply argv {:out golden/ffprobe-json})
      (= "ffprobe" (first argv)) (golden/process-reply argv {:exit 1 :out golden/ffprobe-error-json :err golden/ffprobe-error-stderr})
      :else (throw (ex-info (str (first argv) " is not installed") {:type :process/binary-missing :binary (first argv)})))))

(defn- migrate-module!
  "What clogem.hub.daemon/migrate-module! does as the registry's :before-start."
  [st m]
  (when-let [resource (get-in m [:db :migrations])]
    (store/migrate! st (:module/id m) resource)))

(defn- with-media
  "`f` with {:reg :store :dir :events :argvs} over a registered media module."
  [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-media-module"})
        b (bus/new-bus)
        st (store/open! {:path (str (fs/path dir "clogem.db")) :bus b})
        events (atom [])
        argvs (atom [])
        rt (runtime/new-runtime {:config config/defaults :bus b :store st :process (fake-ffprobe argvs)})
        reg (registry/new-registry {:runtime rt :bus b :before-start (partial migrate-module! st)})]
    (doseq [t [:media/registered :resource/updated]]
      (bus/subscribe! b t #(swap! events conj %)))
    (try
      (let [entry (registry/register! reg media-manifest)]
        (is (= :ready (:status entry)) (pr-str (:reason entry)))
        (f {:reg reg :store st :dir dir :events events :argvs argvs}))
      (finally
        (registry/stop-all! reg)
        (store/close! st)
        (bus/stop! b)
        (support/delete-tree! dir)))))

(defn- tool [reg name]
  (let [{:keys [handler ctx]} (registry/find-tool reg :admin name)]
    (fn [args] (handler ctx args))))

(defn- rows [st]
  (store/query st "SELECT id, path, size, duration_ms, probe IS NOT NULL AS has_probe FROM media_items ORDER BY created_ms, id"))

(defn- write-file! [dir name text]
  (let [p (str (fs/path dir name))]
    (fs/write-bytes p (.getBytes ^String text "UTF-8"))
    p))

(deftest the-manifest-is-valid
  (is (= {:ok? true :problems []} (manifest/check media-manifest)))
  (is (= ["ffprobe"] (get-in media-manifest [:module/requires :bins])))
  (is (= "clogem/module/media/migrations" (get-in media-manifest [:db :migrations]))))

(deftest the-module-registers-ready-and-serves-both-tools-to-every-profile
  (with-media
    (fn [{:keys [reg argvs]}]
      (is (= [["ffprobe" "-version"]] @argvs) "the health check ran ffprobe -version through the runtime")
      (is (= {:status :ok :modules [{:id :media :status :ready :reason nil}]} (registry/status reg)))
      (doseq [profile [:gui :llm-small :llm-large :admin]]
        (is (= ["media_probe" "media_register"] (map (comp :name :tool) (registry/tools-for reg profile))) (str profile)))
      (let [{:keys [templates resources]} (registry/resource-index reg :llm-small)]
        (is (empty? resources))
        (is (= ["clogem://media/item/{id}"] (map (comp :uri-template :template) templates)))))))

(deftest register-probe-relink-replace-and-read-through-the-real-runtime
  (with-media
    (fn [{:keys [reg store dir events argvs]}]
      (let [register (tool reg "media_register")
            probe (tool reg "media_probe")
            p (write-file! dir "clip.mp4" "clip-one")
            r (register {:path p})
            id (:id r)]
        (testing "register creates a row and publishes :media/registered"
          (is (re-matches #"m-[0-9a-f]{32}" id))
          (is (= {:path p :size 8 :duration-ms golden/duration-ms :created true :relinked false} (dissoc r :id :fingerprint)))
          (is (re-matches #"[0-9a-f]{64}" (:fingerprint r)))
          (is (= [{:id id :path p :size 8 :duration_ms golden/duration-ms :has_probe 1}] (rows store)))
          (is (= ["ffprobe" "-v" "error" "-print_format" "json" "-show_format" "-show_streams" "-show_error" p] (second @argvs)))
          (is (wait-for #(= 2 (count @events)) 3000))
          (is (= #{[:media/registered :media {:id id :path p}]
                   [:resource/updated :media {:uri (str "clogem://media/item/" id)}]}
                 (set (map (juxt :event/type :event/source :event/payload) @events)))
              "both events; each subscription delivers on its own thread, so their order is not fixed"))
        (testing "re-register is idempotent"
          (is (= (assoc r :created false) (register {:path p})))
          (is (= 1 (count (rows store))))
          (Thread/sleep 50)
          (is (= 2 (count @events)) "no new event"))
        (testing "a moved file relinks"
          (let [moved (str (fs/path dir "moved" "clip.mp4"))]
            (fs/create-dirs (fs/parent moved))
            (fs/move p moved)
            (let [r2 (register {:path moved})]
              (is (= [id moved false true] ((juxt :id :path :created :relinked) r2)))
              (is (= [{:id id :path moved :size 8 :duration_ms golden/duration-ms :has_probe 1}] (rows store)))
              (is (wait-for #(= 4 (count @events)) 3000))
              (is (= #{[:media/registered {:id id :path moved}]
                       [:resource/updated {:uri (str "clogem://media/item/" id)}]}
                     (set (map (juxt :event/type :event/payload) (drop 2 @events))))
                  "the relink's two events, in either order"))
            (testing "a changed file gets a new id and the old row loses its path"
              (fs/write-bytes moved (.getBytes "clip-two" "UTF-8"))
              (let [r3 (register {:path moved})
                    new-id (:id r3)]
                (is (not= id new-id))
                (is (= [moved true false] ((juxt :path :created :relinked) r3)))
                (is (= [{:id id :path nil :size 8 :duration_ms golden/duration-ms :has_probe 1}
                        {:id new-id :path moved :size 8 :duration_ms golden/duration-ms :has_probe 1}]
                       (rows store)))
                (is (wait-for #(= 7 (count @events)) 3000))
                (is (= #{(str "clogem://media/item/" id) (str "clogem://media/item/" new-id)}
                       (set (map (comp :uri :event/payload) (filter #(= :resource/updated (:event/type %)) (drop 4 @events)))))
                    "both items changed")
                (testing "probe by id and by path"
                  (let [by-id (probe {:id new-id})
                        by-path (probe {:path moved})
                        runs-before (count @argvs)]
                    (is (= golden/duration-ms (:duration-ms by-id)))
                    (is (= ["h264" "aac"] (map :codec_name (:streams by-id))))
                    (is (= "mov,mp4,m4a,3gp,3g2,mj2" (get-in by-id [:format :format_name])))
                    (is (= by-id by-path))
                    (is (= runs-before (count @argvs)) "answered from the stored document, no ffprobe run")
                    (is (= golden/duration-ms (:duration-ms (probe {:path (write-file! dir "other.mp4" "unregistered")}))))
                    (is (= (inc runs-before) (count @argvs)) "an unregistered file is probed")))
                (testing "the old row has no file to probe any more"
                  (let [e (try (probe {:id id}) nil (catch clojure.lang.ExceptionInfo e e))]
                    (is (= :media/not-found (:type (ex-data e))))
                    (is (= id (:id (ex-data e))))))
                (testing "the item resource returns the row as JSON, nil for an unknown id"
                  (let [{:keys [handler ctx template]} (first (:templates (registry/resource-index reg :gui)))
                        read (fn [item-id] (handler ctx {:uri (str "clogem://media/item/" item-id) :params {:id item-id}}))
                        r (read new-id)]
                    (is (= "application/json" (:mime-type template)))
                    (is (= "application/json" (:mime-type r)))
                    (let [doc (json/parse-string (:text r) true)]
                      (is (= {:id new-id :path moved :size 8 :duration_ms golden/duration-ms} (select-keys doc [:id :path :size :duration_ms])))
                      (is (integer? (:created_ms doc)))
                      (is (= "95.400000" (get-in doc [:probe :format :duration]))))
                    (is (nil? (:path (json/parse-string (:text (read id)) true))) "the displaced row reads with a null path")
                    (is (nil? (read "m-nope")))))))))
        (testing "a missing file is refused before any process runs"
          (let [runs (count @argvs)
                e (try (register {:path (str (fs/path dir "nope.mp4"))}) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :media/not-found (:type (ex-data e))))
            (is (= runs (count @argvs)))))))))

(deftest without-a-usable-ffprobe-the-module-is-degraded-not-unavailable
  (let [b (bus/new-bus)
        register-with (fn [process]
                        (let [rt (runtime/new-runtime (cond-> {:config config/defaults :bus b} process (assoc :process process)))
                              reg (registry/new-registry {:runtime rt :bus b})
                              entry (registry/register! reg media-manifest)]
                          (try
                            [entry (map (comp :name :tool) (registry/tools-for reg :admin))]
                            (finally (registry/stop-all! reg)))))]
    (try
      (testing "ffprobe not installed (:process/binary-missing)"
        (let [[e tools] (register-with (fn [_ _ _] (throw (ex-info "not installed" {:type :process/binary-missing :binary "ffprobe"}))))]
          (is (= :degraded (:status e)))
          (is (= {:type :health :health {:status :degraded :error "ffprobe not found" :type :process/binary-missing}} (:reason e)))
          (is (= ["media_probe" "media_register"] tools) "still serving")))
      (testing "ffprobe -version exits non-zero"
        (let [[e _] (register-with (fn [_ argv _] (golden/process-reply argv {:exit 1 :err "bad binary"})))]
          (is (= :degraded (:status e)))
          (is (= {:status :degraded :error "ffprobe not found" :exit 1} (get-in e [:reason :health])))))
      (testing "a runtime without a process function"
        (let [[e _] (register-with nil)]
          (is (= :degraded (:status e)))
          (is (re-find #"ffprobe check failed" (get-in e [:reason :health :error])))))
      (testing "ffprobe present"
        (let [[e _] (register-with (fn [_ argv _] (golden/process-reply argv {:out "ffprobe version 7.1\n"})))]
          (is (= :ready (:status e)))))
      (finally (bus/stop! b)))))
