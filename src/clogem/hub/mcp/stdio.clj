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
   sentinel when needed), for modern bodies MCP-Protocol-Version, and the
   bearer token of the proxy's own client from clients.edn (`:stdio :client`,
   overridable with CLOGEM_CLIENT; the file is readable by this user only).
   A 202 produces no output; any JSON body is written as one line. A modern
   `subscriptions/listen` opens a streaming POST whose SSE events are written
   as frames until the client cancels it (`notifications/cancelled` with that
   request id) or the daemon ends it; if the stream ends without the listen
   result, the proxy emits the `notifications/cancelled` the stdio transport
   requires. When no daemon answers, the proxy starts one (`bb daemon`
   detached, run by the Babashka binary of this proxy) and waits for
   daemon.edn. stdout carries frames only; everything else goes to stderr
   through clogem.hub.log."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.hub.auth :as auth]
            [clogem.hub.config :as config]
            [clogem.hub.daemon :as daemon]
            [clogem.hub.log :as log]
            [clogem.hub.mcp.http :as mcp-http]
            [clogem.hub.mcp.jsonrpc :as rpc]
            [clogem.hub.mcp.methods :as methods]
            [clogem.hub.platform :as platform]))

;; ---------------------------------------------------------------------------
;; Finding (or starting) the daemon

(defn daemon-url
  "The running daemon's endpoint: from daemon.edn (in `runtime-dir`, default
   the configured runtime directory) when a live daemon has recorded itself
   (it may run on another port or with another environment than this proxy),
   else from the configuration."
  ([config] (daemon-url config {}))
  ([config {:keys [runtime-dir]}]
   (let [info (if runtime-dir
                (daemon/read-daemon-file (daemon/daemon-file-path runtime-dir))
                (daemon/read-daemon-file))
         port (or (:port info) (get-in config [:http :port]))]
     (str "http://127.0.0.1:" port mcp-http/endpoint))))

(defn reachable?
  "True when something accepts TCP connections at the daemon's port."
  [url]
  (let [port (some-> (re-find #":(\d+)/" url) second parse-long)]
    (boolean (and port
                  (try (with-open [s (java.net.Socket.)]
                         (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 500)
                         true)
                       (catch Exception _ false))))))

(defn hub-dir
  "The hub checkout this proxy runs from (the directory of its bb.edn)."
  []
  (some-> (System/getProperty "babashka.config") fs/absolutize fs/parent str))

(defn start-daemon!
  "Start a daemon for this user without waiting for it: a detached
   `bb daemon` run by the Babashka binary of this proxy (the packaged bb on
   Windows), whose stderr goes to <runtime-dir>/daemon.log (its stdout is
   empty by design). `:extra-env` reaches the spawned daemon (tests point it
   at temporary directories). Returns how it was started."
  [{:keys [runtime-dir extra-env]}]
  (let [dir (or runtime-dir (config/runtime-dir))
        hub (or (hub-dir) (throw (ex-info "cannot locate the hub checkout to start a daemon" {:type :no-hub-dir})))
        log-file (fs/file (fs/path dir "daemon.log"))]
    (fs/create-dirs dir)
    (p/process (cond-> {:dir hub :out :discard :err log-file}
                 (seq extra-env) (assoc :extra-env extra-env))
               (platform/current-binary) "--config" (str (fs/path hub "bb.edn")) "daemon")
    :spawned))

(defn ensure-daemon!
  "The endpoint of a reachable daemon, starting one when allowed
   (`:stdio :autostart?`) and waiting up to `:stdio :start-timeout-ms` for
   daemon.edn to appear. Returns nil when none can be reached."
  ([config] (ensure-daemon! config {}))
  ([config {:keys [runtime-dir] :as opts}]
   (let [url (daemon-url config opts)
         recorded? (fn [] (some? (if runtime-dir
                                   (daemon/read-daemon-file (daemon/daemon-file-path runtime-dir))
                                   (daemon/read-daemon-file))))]
     (cond
       (reachable? url) url

       (not (get-in config [:stdio :autostart?]))
       (do (log/error {:msg "clogem-hub daemon not reachable and autostart is off" :url url}) nil)

       :else
       (let [how (start-daemon! opts)
             deadline (+ (System/currentTimeMillis) (get-in config [:stdio :start-timeout-ms]))]
         (log/info {:msg "starting clogem-hub daemon" :how how})
         (loop []
           (let [url (daemon-url config opts)]
             (cond
               (and (recorded?) (reachable? url))
               (do (log/info {:msg "clogem-hub daemon started" :url url}) url)

               (< (System/currentTimeMillis) deadline)
               (do (Thread/sleep 100) (recur))

               :else
               (do (log/error {:msg "clogem-hub daemon did not come up in time" :how how}) nil)))))))))

(defn client-token
  "The bearer token of the proxy's client (CLOGEM_CLIENT or `:stdio :client`)
   from clients.edn (`:clients-file` overrides the configured path), or nil
   with one error line."
  ([config] (client-token config {}))
  ([config {:keys [clients-file]}]
  (let [id (or (not-empty (System/getenv "CLOGEM_CLIENT")) (get-in config [:stdio :client]))
        path (or clients-file (config/clients-file config))]
    (try (or (auth/client-token (auth/read-clients path) id)
             (do (log/error {:msg "stdio client missing from clients.edn" :client id :path path}) nil))
         (catch Exception e
           (log/error {:msg "clients.edn unreadable" :path path :error (ex-message e)})
           nil)))))

;; ---------------------------------------------------------------------------
;; Forwarding

(defn headers-for
  "HTTP headers mirroring a parsed JSON-RPC message, plus the bearer token."
  ([message] (headers-for message nil))
  ([message token]
   (let [method (:method message)
         mcp-name (or (get-in message [:params :name]) (get-in message [:params :uri]))
         version (methods/requested-version message)]
     (cond-> {"Content-Type" "application/json"
              "Accept" "application/json, text/event-stream"}
       token (assoc "Authorization" (str "Bearer " token))
       (string? method) (assoc "Mcp-Method" method)
       (and (string? method) (contains? #{"tools/call" "resources/read" "prompts/get"} method) mcp-name)
       (assoc "Mcp-Name" (mcp-http/encode-header-value (str mcp-name)))
       (string? version) (assoc "MCP-Protocol-Version" version)))))

(defn- local-error [message code text data]
  (rpc/encode (rpc/error-response (when (and (map? message) (rpc/valid-id? (:id message))) (:id message))
                                  code text data)))

(defn forward!
  "Forward one frame to the daemon. Returns the frame to write back, or nil
   when nothing must be written (202, or a notification without a reply)."
  ([url line] (forward! url nil line))
  ([url token line]
   (let [{:keys [message error]} (rpc/parse line)]
     (if error
       (rpc/encode error)
       (try
         (let [{:keys [status body]} (http/post url {:headers (headers-for message token)
                                                     :body line
                                                     :throw false
                                                     :timeout 60000})]
           (cond
             (= 202 status) nil
             (and (string? body) (not (str/blank? body))) body
             :else (local-error message rpc/internal-error "Internal error"
                                {:reason "empty response from daemon" :status status})))
         (catch Exception e
           (log/error {:msg "clogem-hub daemon not reachable" :url url :error (ex-message e)})
           (if (and (map? message) (contains? message :id))
             (local-error message rpc/internal-error "Internal error: clogem-hub daemon unavailable"
                          {:reason "daemon unavailable" :url url})
             nil)))))))

(defn- emit! [^java.io.Writer out ^String frame]
  (locking out
    (.write out frame)
    (.write out "\n")
    (.flush out)))

(defn- listen?
  "A modern subscriptions/listen request."
  [message]
  (and (map? message) (= "subscriptions/listen" (:method message))
       (contains? message :id) (= :modern (methods/era message))))

(defn- cancelled-frame [id reason]
  (rpc/encode {:jsonrpc "2.0" :method "notifications/cancelled" :params {:requestId id :reason reason}}))

(defn- pump-sse!
  "Write every `data:` event of an SSE stream as one frame; remember whether
   the daemon ended it with the listen result."
  [^java.io.BufferedReader rdr out]
  (loop [data nil completed? false]
    (let [line (.readLine rdr)]
      (cond
        (nil? line) completed?
        (str/blank? line) (do (when data (emit! out data))
                              (recur nil (or completed? (boolean (and data (re-find #"\"result\"" data))))))
        (str/starts-with? line "data: ") (recur (str data (when data "\n") (subs line 6)) completed?)
        (str/starts-with? line "data:") (recur (str data (when data "\n") (str/triml (subs line 5))) completed?)
        :else (recur data completed?)))))

(defn- listen!
  "Open the stream for a listen request and pump it on its own thread.
   Returns the handle `cancel!` closes, or writes a local error."
  [url token message line out streams]
  (let [id (:id message)
        resp (try (http/post url {:headers (headers-for message token) :body line :as :stream :throw false :timeout 60000})
                  (catch Exception e
                    (emit! out (local-error message rpc/internal-error "Internal error: clogem-hub daemon unavailable"
                                            {:reason "daemon unavailable" :url url :error (ex-message e)}))
                    nil))]
    (when resp
      (if (and (= 200 (:status resp))
               (str/starts-with? (str (get-in resp [:headers "content-type"])) "text/event-stream"))
        (let [^java.io.InputStream in (:body resp)
              thread (Thread. (fn []
                                (let [completed? (try (pump-sse! (io/reader in) out)
                                                      (catch Exception _ false))]
                                  (swap! streams dissoc id)
                                  (when-not completed?
                                    (emit! out (cancelled-frame id "stream closed"))))))]
          (swap! streams assoc id {:in in :thread thread})
          (.setDaemon thread true)
          (.start thread))
        ;; a JSON answer (an error): one frame, as for any request
        (let [body (if (string? (:body resp)) (:body resp) (slurp (:body resp)))]
          (emit! out (if (str/blank? body)
                       (local-error message rpc/internal-error "Internal error" {:status (:status resp)})
                       body)))))))

(defn- cancel!
  "Close the stream a client cancellation names; true when there was one."
  [streams message]
  (when-let [id (get-in message [:params :requestId])]
    (when-let [{:keys [in]} (get @streams id)]
      (swap! streams dissoc id)
      (try (.close ^java.io.InputStream in) (catch Exception _ nil))
      true)))

(defn run!
  "Pump frames from `in` to the daemon at `url` and replies to `out` until EOF."
  ([in out url] (run! in out url nil))
  ([^java.io.Reader in ^java.io.Writer out url token]
   (let [reader (java.io.BufferedReader. in)
         streams (atom {})]
     (loop []
       (when-let [line (.readLine reader)]
         (when-not (str/blank? line)
           (let [{:keys [message error]} (rpc/parse line)]
             (cond
               error (emit! out (rpc/encode error))
               (listen? message) (listen! url token message line out streams)
               (and (= "notifications/cancelled" (:method message)) (cancel! streams message)) nil
               :else (when-let [reply (forward! url token line)] (emit! out reply)))))
         (recur)))
     (doseq [{:keys [in]} (vals @streams)] (try (.close ^java.io.InputStream in) (catch Exception _ nil))))))

(defn -main [& _args]
  (let [config (config/load-config)
        url (or (ensure-daemon! config) (daemon-url config))
        token (client-token config)]
    (log/info {:msg "stdio proxy started" :daemon url :client (or (not-empty (System/getenv "CLOGEM_CLIENT")) (get-in config [:stdio :client]))})
    (run! *in* *out* url token)
    (log/info {:msg "stdio proxy: stdin closed, exiting"})))
