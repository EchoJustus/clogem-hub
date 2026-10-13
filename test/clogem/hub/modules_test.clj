;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.modules-test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.bus :as bus]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]
            [clogem.hub.modules :as modules]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.hub.test-support :as support]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def ^:dynamic *bus* nil)
(def ^:dynamic *dir* nil)

(use-fixtures :each
  (fn [f]
    (binding [*bus* (bus/new-bus)
              *dir* (fs/create-temp-dir {:prefix "clogem-loader"})]
      (try (f)
           (finally
             (bus/stop! *bus*)
             (support/delete-tree! *dir*))))))

(defn- fresh-registry
  "A registry on a runtime without a database (the registry_test pattern)."
  []
  (let [rt (runtime/new-runtime {:config config/defaults :bus *bus*})
        reg (registry/new-registry {:runtime rt :bus *bus*})]
    (runtime/attach-registry! rt reg)
    reg))

;; The temporary module: id :loadertest, used by no other test namespace.
(def module-id :loadertest)

(defn- module-source
  "A valid clogem.module.<id>.core namespace: the entry map and one tool."
  [id]
  (str "(ns clogem.module." (name id) ".core (:require [clogem.api :as api]))\n"
       "(def module {:start (fn [ctx] (api/log ctx :info {:msg \"" (name id) " started\"}) {:up true})"
       " :stop (fn [_] nil) :health (fn [_] {:status :ok})})\n"
       "(defn ping [_ctx {:keys [n]}] {:pong true :n (or n 0)})\n"))

(defn- manifest-for
  "A manifest for module `id` whose tool handler is `handler`."
  [id handler]
  {:manifest/version 1 :module/id id :module/version "0.0.1" :module/license :epl-2.0
   :module/description "A temporary module written by the loader tests."
   :module/entry (symbol (str "clogem.module." (name id) ".core") "module")
   :module/requires {:clogem/api "1" :bins [] :env #{}}
   :bus {:publishes {} :subscribes #{}}
   :db {:migrations nil}
   :mcp {:tools [{:name (str (name id) "_ping") :title "Ping"
                  :description "Reply pong. Use it to check that an external module is served."
                  :handler handler
                  :input [:map {:closed true} [:n {:optional true} :int]]
                  :output [:map [:pong :boolean] [:n :int]]
                  :annotations {:readOnlyHint true :idempotentHint true} :meta {}
                  :profiles #{:gui :llm-small :llm-large :admin}}]
         :resources [] :resource-templates [] :prompts []}
   :ui {:card {:title "Loader test" :icon "puzzle" :quick-actions []} :routes []}})

(defn- write-module!
  "Write a module under `root`: sources for `code-id`, the manifest under
   `manifest-dir-id` (the directory the loader derives from the entry's id)
   declaring `manifest` (default: a valid manifest for `code-id`)."
  ([root] (write-module! root {}))
  ([root {:keys [code-id manifest-dir-id manifest]
          :or {code-id module-id}}]
   (let [manifest-dir-id (or manifest-dir-id code-id)
         manifest (or manifest (manifest-for code-id (symbol (str "clogem.module." (name code-id) ".core") "ping")))
         src (fs/path root "src" "clogem" "module" (name code-id))
         res (fs/path root "resources" "clogem" "module" (name manifest-dir-id))]
     (fs/create-dirs src)
     (fs/create-dirs res)
     (spit (str (fs/path src "core.clj")) (module-source code-id))
     (spit (str (fs/path res "manifest.edn")) (pr-str manifest))
     (str root))))

(defn- write-modules-edn!
  "Write `entries` as modules.edn under *dir* (or `file`) and return its path."
  ([entries] (write-modules-edn! (fs/path *dir* "modules.edn") entries))
  ([file entries]
   (spit (str file) (pr-str {:modules entries}))
   (str file)))

(defn- canonical [p] (str (fs/normalize (fs/absolutize p))))

(defn- classpath-count [dir]
  (count (filter #(= (canonical dir) (canonical %)) (cp/split-classpath (cp/get-classpath)))))

(defn- served-ping
  "Call the module's tool through the registry, as the MCP layer would."
  [reg]
  (let [{:keys [handler ctx]} (registry/find-tool reg :admin "loadertest_ping")]
    (handler ctx {:n 3})))

(deftest absolute-and-relative-roots-load-and-serve
  (let [root (write-module! (fs/path *dir* "mods" "loadertest"))
        reg (fresh-registry)]
    (testing "an absolute root"
      (let [report (modules/load-all! reg (write-modules-edn! [{:id module-id :root root :enabled? true}]))]
        (is (= {:loaded [:loadertest] :failed [] :skipped [] :problems []} report))
        (is (= [:loadertest] (registry/module-ids reg)))
        (is (= {:status :ok :modules [{:id :loadertest :status :ready :reason nil}]} (registry/status reg)))
        (is (= {:pong true :n 3} (served-ping reg)) "the external module's tool is served")
        (is (= 1 (classpath-count (fs/path root "src"))))
        (is (= 1 (classpath-count (fs/path root "resources"))))))
    (testing "a relative root resolves against the directory of modules.edn"
      (let [{:keys [entries problems]} (modules/read-entries (write-modules-edn! [{:id module-id :root (str (fs/path "mods" "loadertest"))}]))]
        (is (= [] problems))
        (is (= [{:id :loadertest :root (canonical root) :declared-root (str (fs/path "mods" "loadertest"))
                 :enabled? true :index 0}]
               entries)))
      (let [report (modules/load-all! reg (write-modules-edn! [{:id module-id :root (str (fs/path "mods" "loadertest"))}]))]
        (is (= [:loadertest] (:loaded report)))
        (is (= [] (:failed report)))
        (is (= :ready (:status (first (registry/modules reg)))))
        (is (= {:pong true :n 3} (served-ping reg)))))
    (testing "each directory joins the classpath once, however often it is loaded"
      (modules/load-all! reg (write-modules-edn! [{:id module-id :root root}]))
      (is (= 1 (classpath-count (fs/path root "src"))))
      (is (= 1 (classpath-count (fs/path root "resources"))))
      (is (= [] (modules/add-root! root)) "nothing left to add"))))

(deftest disabled-entries-are-skipped
  (let [root (write-module! (fs/path *dir* "loadertest"))
        reg (fresh-registry)
        report (modules/load-all! reg (write-modules-edn! [{:id module-id :root root :enabled? false}]))]
    (is (= {:loaded [] :failed [] :skipped [:loadertest] :problems []} report))
    (is (= [] (registry/module-ids reg)) "a disabled module is never registered")))

(deftest a-missing-root-fails-that-entry-only
  (let [root (write-module! (fs/path *dir* "loadertest"))
        reg (fresh-registry)
        report (modules/load-all! reg (write-modules-edn! [{:id :loaderghost :root "missing"}
                                                           {:id module-id :root root}]))]
    (is (= [:loadertest] (:loaded report)))
    (is (= [{:id :loaderghost :reason {:type :root-missing :root (canonical (fs/path *dir* "missing"))}}]
           (:failed report)))
    (is (= [] (:problems report)))
    (is (= [:loadertest] (registry/module-ids reg)))))

(deftest a-root-without-a-manifest-fails
  (let [root (fs/path *dir* "empty")
        _ (fs/create-dirs (fs/path root "src"))
        reg (fresh-registry)
        report (modules/load-all! reg (write-modules-edn! [{:id module-id :root (str root)}]))]
    (is (= [] (:loaded report)))
    (is (= [{:id :loadertest :reason {:type :manifest-missing
                                      :path (modules/manifest-path (canonical root) :loadertest)}}]
           (:failed report)))
    (is (= [] (registry/module-ids reg)))))

(deftest an-unreadable-manifest-fails
  (let [root (fs/path *dir* "broken")
        res (fs/path root "resources" "clogem" "module" "loadertest")
        _ (fs/create-dirs res)
        _ (spit (str (fs/path res "manifest.edn")) "[:not :a :map]")
        reg (fresh-registry)
        report (modules/load-all! reg (write-modules-edn! [{:id module-id :root (str root)}]))]
    (is (= [:loadertest] (mapv :id (:failed report))))
    (is (= :manifest-unreadable (get-in report [:failed 0 :reason :type])))
    (is (re-find #"not an EDN map" (get-in report [:failed 0 :reason :message])))
    (is (= [] (registry/module-ids reg)))))

(deftest an-id-mismatch-fails-before-registration
  (let [root (write-module! (fs/path *dir* "alias") {:code-id module-id :manifest-dir-id :loaderalias})
        reg (fresh-registry)
        report (modules/load-all! reg (write-modules-edn! [{:id :loaderalias :root root}]))]
    (is (= [] (:loaded report)))
    (is (= [{:id :loaderalias :reason {:type :id-mismatch :expected :loaderalias :actual :loadertest}}]
           (:failed report)))
    (is (= [] (registry/module-ids reg)) "nothing reached the registry")))

(deftest a-handler-outside-the-module-prefix-registers-as-unavailable
  (let [manifest (manifest-for module-id 'clogem.module.other.core/ping)
        root (write-module! (fs/path *dir* "loadertest") {:manifest manifest})
        reg (fresh-registry)
        report (modules/load-all! reg (write-modules-edn! [{:id module-id :root root}]))]
    (is (= [] (:loaded report)))
    (is (= [:loadertest] (mapv :id (:failed report))))
    (let [{:keys [reason registered?]} (first (:failed report))]
      (is (true? registered?) "the registry holds the module, as :unavailable")
      (is (= :invalid-manifest (:type reason)))
      (is (some #(re-find #"handler clogem.module.other.core/ping must resolve under clogem.module.loadertest\." %)
                (:problems reason))
          (pr-str reason)))
    (is (= [:loadertest] (registry/module-ids reg)))
    (is (= :unavailable (:status (first (registry/modules reg)))))
    (is (= :invalid-manifest (get-in (first (registry/modules reg)) [:reason :type])))
    (is (empty? (registry/tools-for reg :admin)) "an unavailable module serves nothing")))

(deftest bad-entry-shapes-are-reported-and-left-out
  (let [root (write-module! (fs/path *dir* "loadertest"))
        file (write-modules-edn! [{:id "loadertest" :root root}
                                  {:root root}
                                  {:id :loaderx :root 3}
                                  "junk"
                                  {:id :loadery :root root :enabled? "yes"}
                                  {:id :loaderz :root root :enabld? true}
                                  {:id module-id :root root}
                                  {:id module-id :root root}])
        {:keys [entries problems]} (modules/read-entries file)]
    (testing "every bad entry is a problem naming its index; the good one survives"
      (is (= [:loadertest] (mapv :id entries)))
      (is (= 7 (count problems)) (pr-str problems))
      (is (every? #(re-find #"^modules\[\d\]: " %) problems))
      (is (re-find #"modules\[0\]: id: " (nth problems 0)))
      (is (re-find #"modules\[1\]: id: " (nth problems 1)))
      (is (re-find #"modules\[2\]: root: " (nth problems 2)))
      (is (re-find #"modules\[3\]: must be a map" (nth problems 3)))
      (is (re-find #"modules\[4\]: enabled\?: " (nth problems 4)))
      (is (re-find #"modules\[5\]: enabld\?: disallowed key" (nth problems 5)))
      (is (re-find #"modules\[7\]: duplicate module id :loadertest" (nth problems 6))))
    (testing "the loader reports the problems and still loads the good entry"
      (let [reg (fresh-registry)
            report (modules/load-all! reg file)]
        (is (= [:loadertest] (:loaded report)))
        (is (= [] (:failed report)))
        (is (= problems (:problems report)))
        (is (= [:loadertest] (registry/module-ids reg)))))))

(deftest unusable-files-are-one-problem-and-load-nothing
  (let [reg (fresh-registry)]
    (doseq [[content expected] [["[:not :a :map]" #"is not an EDN map"]
                                ["{:modules {:id :loadertest}}" #":modules must be a vector of entries"]
                                ["{:modules [" #"cannot read .*modules\.edn"]]]
      (let [file (str (fs/path *dir* "modules.edn"))
            _ (spit file content)
            {:keys [entries problems exists?]} (modules/read-entries file)
            report (modules/load-all! reg file)]
        (is (true? exists?))
        (is (= [] entries))
        (is (= 1 (count problems)) (pr-str problems))
        (is (re-find expected (first problems)) (pr-str problems))
        (is (= {:loaded [] :failed [] :skipped [] :problems problems} report))
        (is (= [] (registry/module-ids reg)))))))

(deftest a-missing-modules-edn-means-zero-external-modules
  (let [file (str (fs/path *dir* "absent" "modules.edn"))
        reg (fresh-registry)]
    (is (= {:entries [] :problems [] :file file :exists? false} (modules/read-entries file)))
    (is (= {:loaded [] :failed [] :skipped [] :problems []} (modules/load-all! reg file)))
    (is (= [] (registry/module-ids reg)))))

(deftest entry-problems-and-root-resolution-are-pure
  (is (= [] (modules/entry-problems {:id :demo :root "x"})))
  (is (= [] (modules/entry-problems {:id :demo :root "x" :enabled? false})))
  (is (= ["id: must be an unqualified keyword matching ^[a-z][a-z0-9-]*$"]
         (modules/entry-problems {:id :Demo :root "x"})))
  (is (= ["id: must be an unqualified keyword matching ^[a-z][a-z0-9-]*$"]
         (modules/entry-problems {:id :a/demo :root "x"})))
  (is (= ["root: should be at least 1 character"] (modules/entry-problems {:id :demo :root ""})))
  (is (= ["root: missing required key"] (modules/entry-problems {:id :demo})))
  (is (= 1 (count (modules/entry-problems [:id :demo]))))
  (testing "roots: absolute strings stay, relative ones resolve against the base, `..` is normalized"
    (let [base (fs/path *dir* "cfg")]
      (is (= (canonical (fs/path *dir* "mods" "demo"))
             (modules/resolve-root base (str (fs/path ".." "mods" "demo")))))
      (is (= (canonical (fs/path *dir* "elsewhere"))
             (modules/resolve-root base (str (fs/path *dir* "elsewhere")))))
      (is (not (str/includes? (modules/resolve-root base "a/./b/../c") "..")))))
  (is (= (str (fs/path "r" "resources" "clogem" "module" "demo" "manifest.edn"))
         (modules/manifest-path "r" :demo))))
