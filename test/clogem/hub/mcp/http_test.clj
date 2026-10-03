;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.http-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.http :as http]
            [clogem.hub.registry :as registry]
            [clogem.hub.runtime :as runtime]
            [clogem.module.echo.core]
            [clogem.sdk.manifest :as manifest]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def port 7788)

(defn- handler
  ([] (handler #{}))
  ([allowed-origins]
   (let [rt (runtime/new-runtime config/defaults)
         reg (registry/new-registry {:runtime rt})]
     (runtime/attach-registry! rt reg)
     (registry/register! reg (manifest/read-manifest (io/resource "clogem/module/system/manifest.edn")))
     (registry/register! reg (manifest/read-manifest (io/resource "clogem/module/echo/manifest.edn")))
     (http/handler {:registry reg :profile :admin :port port :allowed-origins allowed-origins}))))

(def modern-meta
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- post
  "A ring request map; `headers` override the defaults."
  [body & [headers]]
  {:request-method :post :uri "/mcp"
   :headers (merge {"host" (str "127.0.0.1:" port) "content-type" "application/json"
                    "accept" "application/json, text/event-stream"}
                   headers)
   :body (if (string? body) body (json/generate-string body))})

(defn- modern-headers [method & [name]]
  (cond-> {"mcp-protocol-version" "2026-07-28" "mcp-method" method}
    name (assoc "mcp-name" name)))

(defn- body-of [res] (json/parse-string (:body res) true))

(deftest modern-happy-path
  (let [h (handler)]
    (let [res (h (post {:jsonrpc "2.0" :id 1 :method "server/discover" :params {:_meta modern-meta}}
                       (modern-headers "server/discover")))]
      (is (= 200 (:status res)))
      (is (= "application/json" (get-in res [:headers "Content-Type"])))
      (is (= ["2026-07-28" "2025-11-25" "2025-06-18" "2025-03-26"] (get-in (body-of res) [:result :supportedVersions]))))
    (let [res (h (post {:jsonrpc "2.0" :id 2 :method "tools/list" :params {:_meta modern-meta}}
                       (modern-headers "tools/list")))]
      (is (= 200 (:status res)))
      (is (= ["echo_say" "system_health" "system_list_modules"] (mapv :name (get-in (body-of res) [:result :tools])))))
    (let [res (h (post {:jsonrpc "2.0" :id 3 :method "tools/call"
                        :params {:name "system_health" :arguments {} :_meta modern-meta}}
                       (modern-headers "tools/call" "system_health")))]
      (is (= 200 (:status res)))
      (is (= "ok" (get-in (body-of res) [:result :structuredContent :status]))))))

(deftest legacy-happy-path
  (let [h (handler)]
    (let [res (h (post {:jsonrpc "2.0" :id 1 :method "initialize"
                        :params {:protocolVersion "2025-11-25" :capabilities {} :clientInfo {:name "t" :version "1"}}}))]
      (is (= 200 (:status res)))
      (is (= "2025-11-25" (get-in (body-of res) [:result :protocolVersion]))))
    (is (= 202 (:status (h (post {:jsonrpc "2.0" :method "notifications/initialized"})))))
    (is (= "" (:body (h (post {:jsonrpc "2.0" :method "notifications/initialized"})))))
    (is (= 200 (:status (h (post {:jsonrpc "2.0" :id 2 :method "ping"})))))
    (testing "a legacy client may echo a supported version header"
      (is (= 200 (:status (h (post {:jsonrpc "2.0" :id 3 :method "ping"} {"mcp-protocol-version" "2025-03-26"}))))))
    (testing "a legacy JSON-RPC response posted by a client is accepted silently"
      (is (= 202 (:status (h (post {:jsonrpc "2.0" :id 9 :result {}}))))))))

(deftest rejections
  (let [h (handler)]
    (testing "bad Host → 403"
      (let [res (h (post {:jsonrpc "2.0" :id 1 :method "ping"} {"host" "evil.example:80"}))]
        (is (= 403 (:status res)))
        (is (nil? (:id (body-of res))))
        (is (= -32600 (get-in (body-of res) [:error :code])))))
    (testing "foreign Origin → 403, loopback and configured origins pass"
      (is (= 403 (:status (h (post {:jsonrpc "2.0" :id 1 :method "ping"} {"origin" "https://evil.example"})))))
      (is (= 403 (:status (h (post {:jsonrpc "2.0" :id 1 :method "ping"} {"origin" "null"})))))
      (is (= 200 (:status (h (post {:jsonrpc "2.0" :id 1 :method "ping"} {"origin" "http://localhost:3000"})))))
      (is (= 200 (:status ((handler #{"app://local-gui"}) (post {:jsonrpc "2.0" :id 1 :method "ping"} {"origin" "app://local-gui"}))))))
    (testing "header/body mismatch → 400 + -32020 with the request id"
      (let [res (h (post {:jsonrpc "2.0" :id 7 :method "tools/call" :params {:name "system_health" :_meta modern-meta}}
                         (modern-headers "tools/list" "system_health")))]
        (is (= 400 (:status res)))
        (is (= 7 (:id (body-of res))))
        (is (= -32020 (get-in (body-of res) [:error :code]))))
      (is (= 400 (:status (h (post {:jsonrpc "2.0" :id 8 :method "tools/call" :params {:name "system_health" :_meta modern-meta}}
                                   (modern-headers "tools/call"))))) "Mcp-Name required for tools/call")
      (is (= 400 (:status (h (post {:jsonrpc "2.0" :id 8 :method "tools/list" :params {:_meta modern-meta}}
                                   {"mcp-method" "tools/list"})))) "MCP-Protocol-Version required")
      (is (= 400 (:status (h (post {:jsonrpc "2.0" :id 8 :method "tools/list" :params {:_meta modern-meta}}
                                   {"mcp-protocol-version" "2025-11-25" "mcp-method" "tools/list"})))) "header must equal the body version"))
    (testing "Base64 sentinel in Mcp-Name is decoded before comparison"
      (let [encoded (http/encode-header-value "system_health ")
            res (h (post {:jsonrpc "2.0" :id 9 :method "tools/call" :params {:name "system_health " :_meta modern-meta}}
                         (modern-headers "tools/call" encoded)))]
        (is (re-matches #"=\?base64\?.*\?=" encoded))
        (is (= 200 (:status res)) "the header matched; the unknown tool is then a -32602 result")
        (is (= -32602 (get-in (body-of res) [:error :code])))))
    (testing "unsupported version → 400 + -32022, modern unknown method → 404"
      (let [res (h (post {:jsonrpc "2.0" :id 10 :method "tools/list"
                          :params {:_meta (assoc modern-meta "io.modelcontextprotocol/protocolVersion" "2099-01-01")}}
                         {"mcp-protocol-version" "2099-01-01" "mcp-method" "tools/list"}))]
        (is (= 400 (:status res)))
        (is (= -32022 (get-in (body-of res) [:error :code]))))
      (is (= 400 (:status (h (post {:jsonrpc "2.0" :id 10 :method "ping"} {"mcp-protocol-version" "2099-01-01"}))))
          "legacy request with an unknown version header")
      (is (= 404 (:status (h (post {:jsonrpc "2.0" :id 11 :method "nope/x" :params {:_meta modern-meta}}
                                   (modern-headers "nope/x")))))))
    (testing "GET → 405 with Allow; other paths → 404"
      (let [res (h {:request-method :get :uri "/mcp" :headers {"host" (str "127.0.0.1:" port)}})]
        (is (= 405 (:status res)))
        (is (= "POST" (get-in res [:headers "Allow"]))))
      (is (= 405 (:status (h {:request-method :delete :uri "/mcp" :headers {}}))))
      (is (= 404 (:status (h {:request-method :post :uri "/other" :headers {}})))))
    (testing "malformed JSON → 400 + -32700; batch → 400 + -32600; wrong content type → 415"
      (let [res (h (post "{nope"))]
        (is (= 400 (:status res)))
        (is (= -32700 (get-in (body-of res) [:error :code]))))
      (is (= -32600 (get-in (body-of (h (post "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"))) [:error :code])))
      (is (= 415 (:status (h (post "{}" {"content-type" "text/plain"})))))
      (is (= 415 (:status (h (post "{}" {"content-type" "application/json-patch+json"})))) "exact media type")
      (is (= 200 (:status (h (post {:jsonrpc "2.0" :id 1 :method "ping"} {"content-type" "application/json; charset=utf-8"})))) "parameters are fine"))))

(deftest header-helpers
  (is (http/host-ok? "127.0.0.1:7788" 7788))
  (is (http/host-ok? "localhost:7788" 7788))
  (is (http/host-ok? "[::1]:7788" 7788))
  (is (not (http/host-ok? "127.0.0.1:7789" 7788)))
  (is (not (http/host-ok? nil 7788)))
  (is (http/host-ok? "localhost" 80))
  (is (= "héllo wörld" (http/decode-header-value (http/encode-header-value "héllo wörld"))))
  (is (= "plain" (http/encode-header-value "plain")))
  (is (= "plain" (http/decode-header-value "plain")))
  (is (not= "=?base64?x?=" (http/encode-header-value "=?base64?x?=")) "sentinel-looking plain values are encoded too"))
