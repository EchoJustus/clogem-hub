;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.stdio-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.auth]
            [clogem.hub.config :as config]
            [clogem.hub.lock]
            [clogem.hub.daemon :as daemon]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.stdio :as stdio]
            [clogem.hub.platform :as platform]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- test-config [dir overrides]
  (config/load-config (merge {:http {:port 0} :db {:path (str (fs/path dir "clogem.db"))}} overrides)))

(defn- with-daemon [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-daemon"})
        system (daemon/start! {:config (test-config dir {}) :runtime-dir (str dir)
                               :clients-file (str (fs/path dir "clients.edn"))})]
    (try (f (assoc system :token (clogem.hub.auth/client-token @(:clients system) "stdio")))
         (finally (daemon/stop! system) (fs/delete-tree dir)))))

(deftest headers-mirror-the-body
  (is (= {"Content-Type" "application/json" "Accept" "application/json, text/event-stream"
          "Mcp-Method" "tools/call" "Mcp-Name" "echo_say" "MCP-Protocol-Version" "2026-07-28"}
         (stdio/headers-for {:method "tools/call"
                             :params {:name "echo_say"
                                      :_meta {(keyword "io.modelcontextprotocol/protocolVersion") "2026-07-28"}}})))
  (is (= {"Content-Type" "application/json" "Accept" "application/json, text/event-stream"
          "Mcp-Method" "initialize"}
         (stdio/headers-for {:method "initialize" :params {:protocolVersion "2025-11-25"}}))
      "legacy bodies carry no version header")
  (is (str/starts-with? (get (stdio/headers-for {:method "resources/read" :params {:uri "clogem://x/y/zé"}}) "Mcp-Name")
                        "=?base64?")
      "non-ASCII names use the sentinel"))

(deftest frames-round-trip-through-a-live-daemon
  (with-daemon
    (fn [{:keys [port token]}]
      (let [url (str "http://127.0.0.1:" port "/mcp")]
        (testing "legacy initialize"
          (let [reply (json/parse-string (stdio/forward! url token (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion "2025-11-25" :capabilities {} :clientInfo {:name "t" :version "1"}}})) true)]
            (is (= "2025-11-25" (get-in reply [:result :protocolVersion])))))
        (testing "notifications produce no frame"
          (is (nil? (stdio/forward! url token "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))))
        (testing "modern tools/call"
          (let [body (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/call"
                                            :params {:name "system_health" :arguments {}
                                                     :_meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                             "io.modelcontextprotocol/clientCapabilities" {}}}})
                reply (json/parse-string (stdio/forward! url token body) true)]
            (is (= "ok" (get-in reply [:result :structuredContent :status])))))
        (testing "without the token the daemon answers 401, which the proxy relays as the JSON-RPC error body"
          (let [reply (json/parse-string (stdio/forward! url nil "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}") true)]
            (is (= -32600 (get-in reply [:error :code])))
            (is (re-find #"Unauthorized" (get-in reply [:error :message])))))
        (testing "a parse error is answered locally"
          (is (= -32700 (get-in (json/parse-string (stdio/forward! url token "{nope") true) [:error :code]))))
        (testing "run! pumps lines and writes one frame per response"
          (let [in (java.io.StringReader. (str "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n"
                                               "\n"
                                               "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                                               "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}\n"))
                out (java.io.StringWriter.)]
            (stdio/run! in out url token)
            (let [lines (str/split-lines (str out))]
              (is (= 2 (count lines)))
              (is (= [3 4] (map #(:id (json/parse-string % true)) lines))))))))))

(deftest a-missing-daemon-is-reported-without-breaking-the-frame-stream
  (let [reply (json/parse-string (stdio/forward! "http://127.0.0.1:1/mcp" "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}") true)]
    (is (= 9 (:id reply)))
    (is (= -32603 (get-in reply [:error :code])))
    (is (= "daemon unavailable" (get-in reply [:error :data :reason]))))
  (is (nil? (stdio/forward! "http://127.0.0.1:1/mcp" "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
      "a notification to a dead daemon is dropped silently"))

(deftest daemon-file-discovery
  (let [dir (fs/create-temp-dir {:prefix "clogem-daemon-file"})
        path (daemon/daemon-file-path (str dir))]
    (try
      (is (nil? (daemon/read-daemon-file path)) "missing file")
      (spit path (pr-str {:pid (.pid (java.lang.ProcessHandle/current)) :port 4242 :host "127.0.0.1"}))
      (is (= 4242 (:port (daemon/read-daemon-file path))) "a live pid is trusted")
      (spit path (pr-str {:pid 999999999 :port 4242}))
      (is (nil? (daemon/read-daemon-file path)) "a dead pid is ignored")
      (spit path "[not a map]")
      (is (nil? (daemon/read-daemon-file path)))
      (finally (fs/delete-tree dir)))))

(deftest start-failures-leave-nothing-behind
  (let [dir (fs/create-temp-dir {:prefix "clogem-daemon-busy"})
        first-system (daemon/start! {:config (test-config dir {}) :runtime-dir (str dir)
                                     :clients-file (str (fs/path dir "clients.edn"))})
        token (clogem.hub.auth/client-token @(:clients first-system) "admin")
        other-dir (fs/create-temp-dir {:prefix "clogem-daemon-busy2"})
        busy (test-config other-dir {:http {:port (:port first-system)}})]
    (try
      (testing "a second daemon of the same user refuses before touching anything"
        (let [e (try (daemon/start! {:config (test-config other-dir {}) :runtime-dir (str dir)}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :already-running (:type (ex-data e))))
          (is (= (.pid (java.lang.ProcessHandle/current)) (:holder (ex-data e))))
          (is (not (fs/exists? (fs/path other-dir "clogem.db"))) "no database was opened")))
      (testing "a port in use fails the start and leaves no lock behind"
        (is (thrown? Exception (daemon/start! {:config busy :runtime-dir (str other-dir)
                                               :clients-file (str (fs/path other-dir "clients.edn"))})))
        (is (not (clogem.hub.lock/held? (str other-dir))) "the failed start released its own lock"))
      (is (= 200 (:status (babashka.http-client/post (str "http://127.0.0.1:" (:port first-system) "/mcp")
                                                      {:headers {"Content-Type" "application/json"
                                                                 "Authorization" (str "Bearer " token)}
                                                       :body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"
                                                       :throw false})))
          "the first daemon keeps serving")
      (finally (daemon/stop! first-system) (fs/delete-tree dir) (fs/delete-tree other-dir)))))

(deftest daemon-lifecycle
  (with-daemon
    (fn [{:keys [port daemon-file registry store bus]}]
      (is (pos? port))
      (is (fs/exists? daemon-file))
      (let [info (clojure.edn/read-string (slurp daemon-file))]
        (is (= port (:port info)))
        (is (= "127.0.0.1" (:host info)))
        (is (pos? (:pid info)))
        (is (= (:path store) (:db info)) "daemon.edn names the database"))
      (is (fs/exists? (:path store)))
      (is (= [:db/tx :system/health :system/modules :system/shutdown] (clogem.hub.bus/owners bus)))
      (is (= [:system] (clogem.hub.registry/module-ids registry)))
      (let [info (clojure.edn/read-string (slurp daemon-file))]
        (is (= (clogem.hub.lock/lock-path (fs/parent daemon-file)) (:lock info)) "daemon.edn names the lock")
        (is (fs/exists? (:clients-file info)) "daemon.edn names the clients file it created")
        (is (clogem.hub.auth/secure-permissions? (:clients-file info)) "clients.edn is owner-only")))))

(deftest the-proxy-starts-a-daemon-when-none-answers
  ;; The spawned daemon runs `bb daemon` from this checkout with its XDG
  ;; directories pointed at a temp tree, so nothing of the user's is touched.
  (let [base (fs/create-temp-dir {:prefix "clogem-autostart"})
        config-dir (str (fs/path base "config"))
        data-dir (str (fs/path base "data"))
        runtime-base (str (fs/path base "run"))
        runtime-dir (str (fs/path runtime-base "clogem"))
        ;; XDG_DATA_HOME also moves Babashka's pod cache, so the child is told where
        ;; the pods already are (a download would be slow and, in some containers, impossible)
        pods-dir (or (System/getenv "BABASHKA_PODS_DIR") (str (fs/path (System/getProperty "user.home") ".babashka" "pods")))
        extra-env {"CLOGEM_CONFIG_DIR" config-dir "XDG_DATA_HOME" data-dir "XDG_RUNTIME_DIR" runtime-base
                   "BABASHKA_PODS_DIR" pods-dir}
        config (config/load-config {:http {:port 0} :stdio {:start-timeout-ms 90000}})]
    (fs/create-dirs config-dir)
    (spit (str (fs/path config-dir "config.edn")) (pr-str {:http {:port 0}}))
    (try
      (is (nil? (daemon/read-daemon-file (daemon/daemon-file-path runtime-dir))) "no daemon yet")
      (let [url (stdio/ensure-daemon! config {:runtime-dir runtime-dir :extra-env extra-env})
            info (daemon/read-daemon-file (daemon/daemon-file-path runtime-dir))]
        (is (string? url))
        (is (some? info) "the spawned daemon recorded itself")
        (is (= url (str "http://127.0.0.1:" (:port info) "/mcp")))
        (is (fs/exists? (fs/path config-dir "clients.edn")) "it created clients.edn in the temp config dir")
        (let [token (stdio/client-token config {:clients-file (str (fs/path config-dir "clients.edn"))})]
          (is (string? token))
          (is (= "ok" (get-in (json/parse-string (stdio/forward! url token (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                                                                                                   :params {:name "system_health" :arguments {}
                                                                                                            :_meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                                                                                    "io.modelcontextprotocol/clientCapabilities" {}}}})) true)
                              [:result :structuredContent :status]))))
        (is (= url (stdio/ensure-daemon! config {:runtime-dir runtime-dir :extra-env extra-env}))
            "a reachable daemon is reused, not started twice")
        (testing "a graceful stop (SIGTERM on POSIX, system_shutdown through the proxy on Windows) ends the spawned daemon cleanly"
          (let [handle (.get (java.lang.ProcessHandle/of (:pid info)))]
            (if (platform/windows?)
              (let [admin (clogem.hub.auth/client-token (clogem.hub.auth/read-clients (str (fs/path config-dir "clients.edn"))) "admin")
                    reply (json/parse-string (stdio/forward! url admin (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/call"
                                                                                                  :params {:name "system_shutdown" :arguments {}
                                                                                                           :_meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                                                                                   "io.modelcontextprotocol/clientCapabilities" {}}}})) true)]
                (is (true? (get-in reply [:result :structuredContent :stopping])) (pr-str reply)))
              (.destroy handle))
            (let [deadline (+ (System/currentTimeMillis) 60000)]
              (loop [] (when (and (.isAlive handle) (< (System/currentTimeMillis) deadline)) (Thread/sleep 100) (recur))))
            (is (not (.isAlive handle)))
            (is (not (fs/exists? (daemon/daemon-file-path runtime-dir))) "daemon.edn removed on stop"))))
      (finally
        (when-let [info (daemon/read-daemon-file (daemon/daemon-file-path runtime-dir))]
          (some-> (java.lang.ProcessHandle/of (:pid info)) .get .destroyForcibly))
        (fs/delete-tree base)))))

(deftest listen-streams-flow-through-the-proxy-as-frames
  ;; The real door: `bb shim` as a child process whose stdin receives frames
  ;; and whose stdout must carry frames only. Its environment points the
  ;; runtime and config directories at the test daemon's temp tree.
  (let [base (fs/create-temp-dir {:prefix "clogem-shim"})
        runtime-dir (str (fs/path base "clogem"))
        config-dir (str (fs/path base "config"))
        _ (fs/create-dirs config-dir)
        _ (spit (str (fs/path config-dir "config.edn")) (pr-str {:stdio {:autostart? false}}))
        system (daemon/start! {:config (test-config base {}) :runtime-dir runtime-dir
                               :clients-file (str (fs/path config-dir "clients.edn"))})]
    (try
      (clogem.hub.registry/register! (:registry system) (clogem.sdk.manifest/read-manifest (clojure.java.io/resource "clogem/module/echo/manifest.edn")))
      (let [meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28" "io.modelcontextprotocol/clientCapabilities" {}}
            listen (json/generate-string {:jsonrpc "2.0" :id 9 :method "subscriptions/listen"
                                          :params {:notifications {:resourceSubscriptions ["clogem://echo/last/said"]} :_meta meta}})
            say (json/generate-string {:jsonrpc "2.0" :id 10 :method "tools/call"
                                       :params {:name "echo_say" :arguments {:text "x"} :_meta meta}})
            cancel (json/generate-string {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId 9}})
            proc (p/process {:dir (System/getProperty "user.dir") :err (fs/file (fs/path base "shim.err"))
                             :extra-env {"XDG_RUNTIME_DIR" (str base) "CLOGEM_CONFIG_DIR" config-dir}}
                            "bb" "--config" "bb.edn" "shim")
            ^java.io.Writer in (clojure.java.io/writer (:in proc))
            frames (atom [])
            reader (future (with-open [r (clojure.java.io/reader (:out proc))]
                             (loop [] (when-let [line (.readLine ^java.io.BufferedReader r)]
                                        (when-not (str/blank? line) (swap! frames conj (json/parse-string line true)))
                                        (recur)))))
            send-line! (fn [l] (.write in (str l "\n")) (.flush in))
            wait-frames (fn [n] (let [deadline (+ (System/currentTimeMillis) 30000)]
                                  (loop [] (when (and (< (count @frames) n) (< (System/currentTimeMillis) deadline)) (Thread/sleep 50) (recur))))
                          @frames)]
        (try
          (send-line! listen)
          (let [fs* (wait-frames 1)]
            (is (= "notifications/subscriptions/acknowledged" (:method (first fs*))) (pr-str fs*))
            (is (= 9 (get-in (first fs*) [:params :_meta (keyword "io.modelcontextprotocol/subscriptionId")]))))
          (send-line! say)
          (let [fs* (wait-frames 3)]
            (is (some #(= "notifications/resources/updated" (:method %)) fs*) (pr-str fs*))
            (is (some #(= 10 (:id %)) fs*) "the call's own response is a frame too"))
          (testing "a client cancellation closes the stream; nothing further arrives for it"
            (send-line! cancel)
            (Thread/sleep 500)
            (send-line! say)
            (let [fs* (wait-frames 5)]
              (is (= 1 (count (filter #(= "notifications/resources/updated" (:method %)) fs*))) "no update after cancel")
              (is (= 2 (count (filter #(= 10 (:id %)) fs*))))
              (is (some #(and (= "notifications/cancelled" (:method %)) (= 9 (get-in % [:params :requestId]))) fs*)
                  "a stream closed without the listen result is announced as cancelled on stdio")))
          (testing "stdout carried frames only"
            (is (every? map? @frames)))
          (finally
            (.close in)
            (is (.waitFor ^java.lang.Process (:proc proc) 20 java.util.concurrent.TimeUnit/SECONDS) "the shim exits on stdin EOF")
            (deref reader 5000 nil)
            (when (.isAlive ^java.lang.Process (:proc proc)) (.destroyForcibly ^java.lang.Process (:proc proc)))
            (let [err (slurp (str (fs/path base "shim.err")))]
              (is (every? #(str/starts-with? % "{") (remove str/blank? (str/split-lines err))) (str "stderr is EDN lines only: " err))))))
      (finally (daemon/stop! system) (fs/delete-tree base)))))
