;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.jsonrpc-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clogem.hub.mcp.jsonrpc :as rpc]))

(defn- dispatch [method params _message]
  (case method
    "ping" {}
    "add" (+ (first params) (second params))
    "echo" params
    "boom" (throw (RuntimeException. "secret internal detail"))
    "bad-params" (throw (rpc/rpc-error rpc/invalid-params "Invalid params" {:field "x"}))
    (throw (rpc/rpc-error rpc/method-not-found (str "Method not found: " method)))))

(defn- run [body] (rpc/handle-body body dispatch))

(deftest requests-get-results
  (is (= {:kind :request :response {:jsonrpc "2.0" :id 1 :result {}}}
         (run "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")))
  (is (= {:jsonrpc "2.0" :id "abc" :result 3}
         (:response (run "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"add\",\"params\":[1,2]}"))))
  (is (= {:a 1} (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"echo\",\"params\":{\"a\":1}}")
                        [:response :result]))))

(deftest notifications-get-no-response
  (is (= {:kind :notification :response nil}
         (run "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")))
  (is (= {:kind :notification :response nil}
         (run "{\"jsonrpc\":\"2.0\",\"method\":\"boom\"}")) "a throwing notification is swallowed"))

(deftest client-responses-are-ignored
  (is (= {:kind :response :response nil}
         (run "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}"))))

(deftest protocol-errors
  (testing "parse error has a null id"
    (is (= {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}}
           (:response (run "{not json")))))
  (testing "batches are rejected"
    (is (= -32600 (get-in (run "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]") [:response :error :code]))))
  (testing "invalid request shapes"
    (is (= -32600 (get-in (run "42") [:response :error :code])))
    (is (= -32600 (get-in (run "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}") [:response :error :code])))
    (is (= -32600 (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":1}") [:response :error :code])))
    (is (= -32600 (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}") [:response :error :code])) "float id")
    (is (= -32600 (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}") [:response :error :code])) "object id")
    (is (= -32600 (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":5}") [:response :error :code])) "scalar params")
    (is (= 1 (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":5}") [:response :id])) "id echoed when valid"))
  (testing "method not found"
    (is (= {:code -32601 :message "Method not found: nope"}
           (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"nope\"}") [:response :error]))))
  (testing "invalid params carry data"
    (is (= {:code -32602 :message "Invalid params" :data {:field "x"}}
           (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"bad-params\"}") [:response :error]))))
  (testing "internal errors never leak the exception message"
    (is (= {:code -32603 :message "Internal error"}
           (get-in (run "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"boom\"}") [:response :error])))))

(deftest encoding-is-compact-json
  (is (= "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
         (rpc/encode (rpc/result-response 1 {:ok true}))))
  (is (= {"jsonrpc" "2.0" "id" nil "error" {"code" -32700 "message" "Parse error"}}
         (json/parse-string (rpc/encode (rpc/error-response nil rpc/parse-error "Parse error"))))))
