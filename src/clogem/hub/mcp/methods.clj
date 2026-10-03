;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.mcp.methods
  "MCP methods over the registry: a dual-era server (docs/mcp-compliance.md).

   Modern (2026-07-28) requests are stateless and self-describing through
   params._meta; legacy (2025-11-25 and earlier) clients use the initialize
   handshake and ping. Both are answered from the same registry projections.
   No business logic lives here: arguments are validated against the tool's
   malli schema and handed to the module handler with the module ctx."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.jsonrpc :as rpc]
            [clogem.hub.registry :as registry]
            [clogem.sdk.manifest :as manifest]))

;; ---------------------------------------------------------------------------
;; Protocol constants

(def modern-version "2026-07-28")
(def legacy-versions ["2025-11-25" "2025-06-18" "2025-03-26"])
(def supported-versions (into [modern-version] legacy-versions))
(def default-legacy-version "2025-11-25")

(def header-mismatch -32020)
(def missing-client-capability -32021)
(def unsupported-protocol-version -32022)

(def meta-protocol-version (keyword "io.modelcontextprotocol/protocolVersion"))
(def meta-client-capabilities (keyword "io.modelcontextprotocol/clientCapabilities"))
(def meta-client-info (keyword "io.modelcontextprotocol/clientInfo"))
(def meta-server-info (keyword "io.modelcontextprotocol/serverInfo"))

(def server-info {:name "clogem-hub" :version config/hub-version})

(def capabilities
  "S01: tools only; listChanged becomes true with subscriptions/listen (S03)."
  {:tools {:listChanged false}})

(def instructions
  (str "Clogem hub: a local-first daemon exposing media tools as modules. "
       "Tool names are prefixed by their module: system_* reports daemon health and the loaded modules; "
       "media_* (from S04) registers, probes and transcribes local media files; "
       "further modules may be loaded at runtime. Start with system_health to see what is available."))

(def list-ttl-ms 60000)
(def cache-scope "private")

;; ---------------------------------------------------------------------------
;; Result shaping

(defn- with-server-info [result]
  (assoc result :_meta {meta-server-info server-info}))

(defn complete
  "A final result: resultType \"complete\" plus serverInfo in _meta."
  [result]
  (with-server-info (assoc result :resultType "complete")))

(defn cacheable
  "A list-like result with the cache hints the spec requires."
  [result]
  (assoc (complete result) :ttlMs list-ttl-ms :cacheScope cache-scope))

;; ---------------------------------------------------------------------------
;; Era and _meta

(defn requested-version
  "The protocol version a request declares in params._meta, or nil."
  [message]
  (get-in message [:params :_meta meta-protocol-version]))

(defn era
  "Which era a message belongs to: :modern when params._meta declares a
   protocol version, :legacy otherwise."
  [message]
  (if (requested-version message) :modern :legacy))

(defn unsupported-version-error
  "Legacy versions are reachable only through the initialize handshake, so a
   modern request that declares one is told which modern versions exist."
  [requested]
  (rpc/rpc-error unsupported-protocol-version
                 (str "Unsupported protocol version: " requested)
                 {:supported [modern-version] :requested requested}
                 400))

(defn check-modern-meta!
  "Enforce the required per-request _meta fields of a modern request."
  [message]
  (let [meta (get-in message [:params :_meta])
        version (get meta meta-protocol-version)]
    (when-not (string? version)
      (throw (rpc/rpc-error rpc/invalid-params
                            "Invalid params: _meta io.modelcontextprotocol/protocolVersion is required"
                            nil 400)))
    (when-not (= modern-version version)
      (throw (unsupported-version-error version)))
    (when-not (map? (get meta meta-client-capabilities))
      (throw (rpc/rpc-error rpc/invalid-params
                            "Invalid params: _meta io.modelcontextprotocol/clientCapabilities is required"
                            nil 400)))))

;; ---------------------------------------------------------------------------
;; Tools

(defn- empty-object-schema? [js]
  (and (= "object" (:type js)) (empty? (:properties js))))

(defn tool->mcp
  "The MCP tool object for a registry tools-for entry."
  [{:keys [tool]}]
  (let [{:keys [name title description input output annotations meta]} tool
        input-js (manifest/json-schema input)]
    (cond-> {:name name
             :description description
             :inputSchema (if (empty-object-schema? input-js)
                            {:type "object" :additionalProperties false}
                            input-js)}
      title (assoc :title title)
      output (assoc :outputSchema (manifest/json-schema output))
      (seq annotations) (assoc :annotations annotations)
      (seq meta) (assoc :_meta meta))))

(defn list-tools [{:keys [registry profile]} params]
  (when-let [cursor (:cursor params)]
    (when-not (= "" cursor)
      (throw (rpc/rpc-error rpc/invalid-params "Invalid params: unknown cursor" {:cursor cursor}))))
  (cacheable {:tools (mapv tool->mcp (registry/tools-for registry profile))}))

(defn- tool-error [message]
  (complete {:content [{:type "text" :text message}] :isError true}))

(defn call-tool [{:keys [registry profile]} params]
  (let [tool-name (:name params)
        {:keys [tool handler ctx] :as found} (when (string? tool-name)
                                               (registry/find-tool registry profile tool-name))]
    (when-not found
      (throw (rpc/rpc-error rpc/invalid-params (str "Unknown tool: " (pr-str tool-name)))))
    (let [raw (or (:arguments params) {})
          input (m/schema (:input tool))
          ;; the advertised inputSchema is JSON: keywords, enums and uuids
          ;; arrive as strings and integers may stand for doubles
          args (when (map? raw) (m/decode input raw mt/json-transformer))]
      (when-not (map? raw)
        (throw (rpc/rpc-error rpc/invalid-params "Invalid params: arguments must be an object")))
      (when-not (m/validate input args)
        (throw (rpc/rpc-error rpc/invalid-params "Invalid params: tool arguments do not match the input schema"
                              {:errors (me/humanize (m/explain input args))})))
      (try
        (let [result (handler ctx args)
              output (some-> (:output tool) m/schema)]
          (if (and output (not (m/validate output result)))
            (do (log/warn {:msg "tool result violates its output schema" :tool tool-name
                           :errors (me/humanize (m/explain output result))})
                (tool-error (str tool-name " returned a result that does not match its output schema")))
            (complete {:content [{:type "text" :text (json/generate-string result)}]
                       :structuredContent result
                       :isError false})))
        (catch Exception e
          (log/warn {:msg "tool failed" :tool tool-name :error (ex-message e)})
          (tool-error (str tool-name " failed: " (or (ex-message e) (.getName (class e))))))))))

;; ---------------------------------------------------------------------------
;; Legacy lifecycle

(defn- negotiate-legacy-version [requested]
  (if (contains? (set legacy-versions) requested) requested default-legacy-version))

(defn initialize [params]
  (complete {:protocolVersion (negotiate-legacy-version (:protocolVersion params))
             :capabilities capabilities
             :serverInfo server-info
             :instructions instructions}))

;; ---------------------------------------------------------------------------
;; Dispatch

(defn method-not-found [method era]
  (rpc/rpc-error rpc/method-not-found (str "Method not found: " method)
                 (when (= :modern era) {:supportedVersions supported-versions})
                 (when (= :modern era) 404)))

(defn dispatch
  "The function clogem.hub.mcp.jsonrpc/handle calls: (dispatch method params message).
   `deps` is {:registry reg :profile :admin}. Throws rpc/rpc-error for protocol
   errors; returns the result map otherwise."
  [deps method params message]
  (let [era (era message)]
    (when (and (contains? message :id) (str/starts-with? (str method) "notifications/"))
      (throw (rpc/rpc-error rpc/invalid-request
                            (str "Invalid Request: " method " is a notification and must not carry an id"))))
    (when (= :modern era) (check-modern-meta! message))
    (case method
      "server/discover"
      (if (= :modern era)
        (cacheable {:supportedVersions supported-versions
                    :capabilities capabilities
                    :instructions instructions})
        ;; a legacy-shaped discover (no _meta) is how a legacy server would be
        ;; probed; answer it so dual-era clients recognize a modern server
        (throw (rpc/rpc-error rpc/invalid-params
                              "Invalid params: _meta io.modelcontextprotocol/protocolVersion is required"
                              {:supportedVersions supported-versions}
                              400)))

      "tools/list" (list-tools deps params)
      "tools/call" (call-tool deps params)

      "initialize"
      (if (= :legacy era)
        (initialize params)
        (throw (rpc/rpc-error rpc/method-not-found "Method not found: initialize (removed in 2026-07-28)"
                              {:supportedVersions supported-versions} 404)))

      "ping"
      (if (= :legacy era) (complete {}) (throw (method-not-found method era)))

      "notifications/initialized" nil
      "notifications/cancelled" nil

      (throw (method-not-found method era)))))

(defn dispatcher
  "A dispatch fn bound to `deps` for clogem.hub.mcp.jsonrpc/handle."
  [deps]
  (fn [method params message] (dispatch deps method params message)))

(defn error-http-status
  "The HTTP status for a protocol error code when the request was modern."
  [code]
  (get {rpc/parse-error 400
        rpc/invalid-request 400
        rpc/method-not-found 404
        header-mismatch 400
        missing-client-capability 400
        unsupported-protocol-version 400}
       code))

(defn describe-tools
  "Names of the tools a profile sees, for logs and tests."
  [{:keys [registry profile]}]
  (mapv (comp :name :tool) (registry/tools-for registry profile)))

(defn ascii-summary
  "One-line summary of a result for debug logs (never the full payload)."
  [result]
  (str/join " " (map name (keys (dissoc result :_meta)))))
