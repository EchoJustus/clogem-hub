;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.stdio
  "The stdio door: a thin proxy from newline-delimited JSON-RPC on stdin/stdout
   to the running daemon's HTTP endpoint (PD-4: one daemon, many doors).

   Each line is forwarded as its own POST with the headers the Streamable HTTP
   binding requires, copied from the body: Mcp-Method, Mcp-Name (Base64
   sentinel when needed) and, for modern bodies, MCP-Protocol-Version. A 202
   produces no output; any JSON body is written as one line. stdout carries
   frames only; everything else goes to stderr through clogem.hub.log."
  (:require [babashka.http-client :as http]
            [clogem.hub.config :as config]
            [clogem.hub.daemon :as daemon]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.http :as mcp-http]
            [clogem.hub.mcp.jsonrpc :as rpc]
            [clogem.hub.mcp.methods :as methods]))

(defn daemon-url
  "The running daemon's endpoint: from daemon.edn when a live daemon has
   recorded itself (it may run on another port or with another environment
   than this proxy), else from the configuration."
  [config]
  (let [info (daemon/read-daemon-file)
        port (or (:port info) (get-in config [:http :port]))]
    (str "http://127.0.0.1:" port mcp-http/endpoint)))

(defn headers-for
  "HTTP headers mirroring a parsed JSON-RPC message."
  [message]
  (let [method (:method message)
        mcp-name (or (get-in message [:params :name]) (get-in message [:params :uri]))
        version (methods/requested-version message)]
    (cond-> {"Content-Type" "application/json"
             "Accept" "application/json, text/event-stream"}
      (string? method) (assoc "Mcp-Method" method)
      (and (string? method) (contains? #{"tools/call" "resources/read" "prompts/get"} method) mcp-name)
      (assoc "Mcp-Name" (mcp-http/encode-header-value (str mcp-name)))
      (string? version) (assoc "MCP-Protocol-Version" version))))

(defn- local-error [message code text data]
  (rpc/encode (rpc/error-response (when (and (map? message) (rpc/valid-id? (:id message))) (:id message))
                                  code text data)))

(defn forward!
  "Forward one frame to the daemon. Returns the frame to write back, or nil
   when nothing must be written (202, or a notification without a reply)."
  [url line]
  (let [{:keys [message error]} (rpc/parse line)]
    (if error
      (rpc/encode error)
      (try
        (let [{:keys [status body]} (http/post url {:headers (headers-for message)
                                                    :body line
                                                    :throw false
                                                    :timeout 60000})]
          (cond
            (= 202 status) nil
            (and (string? body) (not (clojure.string/blank? body))) body
            :else (local-error message rpc/internal-error "Internal error"
                               {:reason "empty response from daemon" :status status})))
        (catch Exception e
          (log/error {:msg "clogem-hub daemon not reachable" :url url :error (ex-message e)})
          (if (and (map? message) (contains? message :id))
            (local-error message rpc/internal-error "Internal error: clogem-hub daemon unavailable"
                         {:reason "daemon unavailable" :url url})
            nil))))))

(defn- emit! [^java.io.Writer out ^String frame]
  (.write out frame)
  (.write out "\n")
  (.flush out))

(defn run!
  "Pump frames from `in` to the daemon at `url` and replies to `out` until EOF."
  [^java.io.Reader in ^java.io.Writer out url]
  (let [reader (java.io.BufferedReader. in)]
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (clojure.string/blank? line)
          (when-let [reply (forward! url line)]
            (emit! out reply)))
        (recur)))))

(defn -main [& _args]
  (let [url (daemon-url (config/load-config))]
    (log/info {:msg "stdio proxy started" :daemon url})
    (run! *in* *out* url)
    (log/info {:msg "stdio proxy: stdin closed, exiting"})))
