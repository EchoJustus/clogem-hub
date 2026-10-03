;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.stdio-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.config :as config]
            [clogem.hub.daemon :as daemon]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.stdio :as stdio]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(defn- with-daemon [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-daemon"})
        system (daemon/start! {:config (config/load-config {:http {:port 0}}) :runtime-dir (str dir)})]
    (try (f system)
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
    (fn [{:keys [port]}]
      (let [url (str "http://127.0.0.1:" port "/mcp")]
        (testing "legacy initialize"
          (let [reply (json/parse-string (stdio/forward! url (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion "2025-11-25" :capabilities {} :clientInfo {:name "t" :version "1"}}})) true)]
            (is (= "2025-11-25" (get-in reply [:result :protocolVersion])))))
        (testing "notifications produce no frame"
          (is (nil? (stdio/forward! url "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))))
        (testing "modern tools/call"
          (let [body (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/call"
                                            :params {:name "system_health" :arguments {}
                                                     :_meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                             "io.modelcontextprotocol/clientCapabilities" {}}}})
                reply (json/parse-string (stdio/forward! url body) true)]
            (is (= "ok" (get-in reply [:result :structuredContent :status])))))
        (testing "a parse error is answered locally"
          (is (= -32700 (get-in (json/parse-string (stdio/forward! url "{nope") true) [:error :code]))))
        (testing "run! pumps lines and writes one frame per response"
          (let [in (java.io.StringReader. (str "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n"
                                               "\n"
                                               "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                                               "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}\n"))
                out (java.io.StringWriter.)]
            (stdio/run! in out url)
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

(deftest daemon-lifecycle
  (with-daemon
    (fn [{:keys [port daemon-file registry]}]
      (is (pos? port))
      (is (fs/exists? daemon-file))
      (let [info (clojure.edn/read-string (slurp daemon-file))]
        (is (= port (:port info)))
        (is (= "127.0.0.1" (:host info)))
        (is (pos? (:pid info))))
      (is (= [:system] (clogem.hub.registry/module-ids registry))))))
