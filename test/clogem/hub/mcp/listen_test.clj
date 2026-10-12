;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.listen-test
  "subscriptions/listen over a live daemon: the SSE stream, its
   acknowledgment, resource updates, list changes, keep-alive and the
   graceful close when the daemon stops."
  (:require [babashka.fs :as fs]
            [clogem.hub.test-support :as support]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.audit :as audit]
            [clogem.hub.auth :as auth]
            [clogem.hub.config :as config]
            [clogem.hub.daemon :as daemon]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.subscriptions :as subs]
            [clogem.hub.registry :as registry]
            [clogem.module.echo.core]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def modern-meta {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                  "io.modelcontextprotocol/clientCapabilities" {}})

(defn- start-daemon [dir]
  (let [system (daemon/start! {:config (config/load-config {:http {:port 0}
                                                            :db {:path (str (fs/path dir "clogem.db"))}
                                                            :subscriptions {:keepalive-ms 300}})
                               :runtime-dir (str dir)
                               :clients-file (str (fs/path dir "clients.edn"))})]
    (registry/register! (:registry system) (manifest/read-manifest (io/resource "clogem/module/echo/manifest.edn")))
    (assoc system :url (str "http://127.0.0.1:" (:port system) "/mcp")
           :token (auth/client-token @(:clients system) "llm"))))

(defn- post [{:keys [url token]} body & [headers]]
  (http/post url {:headers (merge {"Content-Type" "application/json" "Authorization" (str "Bearer " token)} headers)
                  :body (json/generate-string body) :throw false}))

(defn- open-listen
  "Open the stream; returns {:response r :reader rdr}."
  [{:keys [url token]} id notifications]
  (let [r (http/post url {:headers {"Content-Type" "application/json" "Accept" "application/json, text/event-stream"
                                    "Authorization" (str "Bearer " token)
                                    "MCP-Protocol-Version" "2026-07-28" "Mcp-Method" "subscriptions/listen"}
                          :body (json/generate-string {:jsonrpc "2.0" :id id :method "subscriptions/listen"
                                                       :params {:notifications notifications :_meta modern-meta}})
                          :as :stream :throw false})]
    {:response r :reader (io/reader (:body r))}))

(defn- next-line [^java.io.BufferedReader rdr] (.readLine rdr))

(defn- next-event
  "The next JSON message on the stream (skipping comments and blanks), or
   nil at EOF. Returns [message comments-seen]."
  [rdr]
  (loop [comments 0]
    (let [l (next-line rdr)]
      (cond
        (nil? l) [nil comments]
        (str/starts-with? l "data: ") [(json/parse-string (subs l 6) true) comments]
        (str/starts-with? l ":") (recur (inc comments))
        :else (recur comments)))))

(deftest the-listen-stream-delivers-what-was_asked-for-and-closes-gracefully
  (let [dir (fs/create-temp-dir {:prefix "clogem-listen"})
        sys (start-daemon dir)]
    (try
      (let [{:keys [response reader]} (open-listen sys 77 {:toolsListChanged true :promptsListChanged true
                                                             :resourceSubscriptions ["clogem://echo/last/said" "clogem://nope/x/y"]})]
        (is (= 200 (:status response)))
        (is (= "text/event-stream" (get-in response [:headers "content-type"])))
        (is (= "no" (get-in response [:headers "x-accel-buffering"])))
        (testing "the acknowledgment comes first and names the honored filter"
          (let [[ack] (next-event reader)]
            (is (= "notifications/subscriptions/acknowledged" (:method ack)))
            (is (= 77 (get-in ack [:params :_meta (keyword "io.modelcontextprotocol/subscriptionId")])))
            (is (= {:toolsListChanged true :resourceSubscriptions ["clogem://echo/last/said"]} (get-in ack [:params :notifications]))
                "prompts are not served; the unknown URI is dropped")))
        (testing "a resource update reaches the subscriber"
          (is (= 200 (:status (post sys {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name "echo_say" :arguments {:text "hi"} :_meta modern-meta}}
                                    {"MCP-Protocol-Version" "2026-07-28" "Mcp-Method" "tools/call" "Mcp-Name" "echo_say"}))))
          (let [[e] (next-event reader)]
            (is (= "notifications/resources/updated" (:method e)))
            (is (= "clogem://echo/last/said" (get-in e [:params :uri])))
            (is (= 77 (get-in e [:params :_meta (keyword "io.modelcontextprotocol/subscriptionId")])))))
        (testing "a registry change becomes tools/list_changed (resources/list_changed was not requested)"
          (registry/unregister! (:registry sys) :echo)
          (let [[e] (next-event reader)]
            (is (= "notifications/tools/list_changed" (:method e))))
          (registry/register! (:registry sys) (manifest/read-manifest (io/resource "clogem/module/echo/manifest.edn")))
          (let [[e] (next-event reader)]
            (is (= "notifications/tools/list_changed" (:method e)))))
        (testing "quiet streams receive keep-alive comments"
          (let [l (loop [n 0] (let [l (next-line reader)]
                                (if (or (nil? l) (str/starts-with? l ":") (> n 20)) l (recur (inc n)))))]
            (is (str/starts-with? (str l) ":") (pr-str l))))
        (testing "the listen request was audited"
          (Thread/sleep 200)
          (is (some #(= "subscriptions/listen" (:method %)) (audit/recent (:store sys) 20))))
        (is (= 1 (:streams (subs/stats (:subscriptions sys)))))
        (testing "stopping the daemon answers the listen request, then closes the stream"
          (daemon/stop! sys)
          (let [[closing] (next-event reader)]
            (is (= 77 (:id closing)))
            (is (= "complete" (get-in closing [:result :resultType])))
            (is (= 77 (get-in closing [:result :_meta (keyword "io.modelcontextprotocol/subscriptionId")])))
            (is (= "clogem-hub" (get-in closing [:result :_meta (keyword "io.modelcontextprotocol/serverInfo") :name]))))
          (is (nil? (first (next-event reader))) "EOF after the result")))
      (finally (try (daemon/stop! sys) (catch Exception _ nil)) (support/delete-tree! dir)))))

(deftest a-client-closing-its-stream-ends-the-subscription
  (let [dir (fs/create-temp-dir {:prefix "clogem-listen2"})
        sys (start-daemon dir)]
    (try
      (let [{:keys [response reader]} (open-listen sys "s-1" {:resourcesListChanged true})]
        (is (= 200 (:status response)))
        (is (= "notifications/subscriptions/acknowledged" (:method (first (next-event reader)))))
        (is (= 1 (:streams (subs/stats (:subscriptions sys)))))
        (.close ^java.io.InputStream (:body response))
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop [] (when (and (pos? (:streams (subs/stats (:subscriptions sys)))) (< (System/currentTimeMillis) deadline))
                     (Thread/sleep 50) (recur))))
        (is (= 0 (:streams (subs/stats (:subscriptions sys)))) "the server noticed the disconnect"))
      (testing "legacy clients get -32601 and no stream; the GET endpoint stays 405"
        (let [r (post sys {:jsonrpc "2.0" :id 5 :method "subscriptions/listen" :params {}})]
          (is (= 200 (:status r)))
          (is (= -32601 (get-in (json/parse-string (:body r) true) [:error :code]))))
        (is (= 405 (:status (http/get (:url sys) {:headers {"Authorization" (str "Bearer " (:token sys)) "Accept" "text/event-stream"} :throw false})))))
      (finally (daemon/stop! sys) (support/delete-tree! dir)))))
