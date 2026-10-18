;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.ui.inbox
  "The inbox behind the built-in `ui` module. Other modules address a GUI
   client by publishing `:ui/command` with the payload {:client string
   :command map}; the command waits in the `ui_inbox` table (migration
   0001) until the client acknowledges it with `ui_ack`. A client's inbox is
   the resource `clogem://ui/inbox/<client>`, and every change to it is
   announced as `:resource/updated` so the hub can notify subscribed
   clients.

   Like every module this one reaches the database only through
   clogem.api/submit-tx! and clogem.api/query. Commands are stored as JSON
   text (cheshire) and handed back as JSON data; a command that is not
   plain data cannot be stored and is dropped with a log line."
  (:require [cheshire.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [clogem.api :as api]))

(def client-re
  "A client id as clients.edn admits it and as a resource URI segment can
   carry it: a letter or digit, then letters, digits, `_`, `.` or `-`, at
   most 64 characters in all."
  ;; \z, not $: malli's :re uses re-find, where $ also matches before a
  ;; trailing newline, and the resource lookup uses re-matches
  #"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\z")

(def Payload
  "What a `:ui/command` payload must look like. Open: a publisher may add
   keys, which the inbox ignores."
  [:map
   [:client [:re {:error/message "must be a client id: [A-Za-z0-9][A-Za-z0-9_.-]{0,63}"} client-re]]
   [:command :map]])

(defn payload-problems
  "Humanized reasons `payload` is not a command, or nil when it is one."
  [payload]
  (some-> (m/explain Payload payload) me/humanize))

(defn client?
  "True when `client` is a usable client id."
  [client]
  (boolean (and (string? client) (re-matches client-re client))))

(defn uri
  "The resource URI of `client`'s inbox."
  [client]
  (str "clogem://ui/inbox/" client))

(defn notify-updated!
  "Tell the hub that `client`'s inbox changed."
  [ctx client]
  (api/publish! ctx :resource/updated {:uri (uri client)}))

(defn- now [] (System/currentTimeMillis))

(defn- refused
  "The exception for a transaction the writer refused."
  [reply what]
  (ex-info (str what " failed: " (pr-str (get-in reply [:error :type])))
           {:type :ui/tx-failed :what what :error (:error reply)}))

(defn store!
  "Insert `command` into `client`'s inbox and announce the change. Returns
   the new row's id. Throws when the command cannot be encoded as JSON or
   when the writer refuses the transaction."
  [ctx client command]
  (let [reply (api/submit-tx! ctx [["INSERT INTO ui_inbox (client, command, created_ms) VALUES (?, ?, ?)"
                                    client (json/generate-string command) (now)]])]
    (when-not (:ok reply)
      (throw (refused reply "storing a ui command")))
    (notify-updated! ctx client)
    (get-in reply [:result :last-inserted-id])))

(defn receive!
  "Handle one `:ui/command` payload: store a valid one and return its row
   id; log and drop an invalid one (a malformed payload, a command that is
   not JSON data, a refused transaction) and return nil. Never throws: the
   bus would report a throwing handler as a bus error, and nothing in the
   event could be fixed by that. The command itself is never logged."
  [ctx payload]
  (if-let [problems (payload-problems payload)]
    (do (api/log ctx :warn {:msg "ui command dropped" :reason :invalid-payload :problems problems})
        nil)
    (try (store! ctx (:client payload) (:command payload))
         (catch Exception e
           (api/log ctx :error {:msg "ui command dropped" :reason :store-failed
                                :client (:client payload) :error (ex-message e)
                                :type (:type (ex-data e))})
           nil))))

(defn- command-of
  "Our own JSON text back to data; the raw text when it does not parse."
  [s]
  (try (json/parse-string s) (catch Exception _ s)))

(defn unacked
  "`client`'s commands not yet acknowledged, oldest first, as
   [{\"id\" n \"command\" {…} \"created_ms\" n} …]. Empty for a client
   nobody wrote to and for anything that is not a client id."
  [ctx client]
  (if (client? client)
    (mapv (fn [{:keys [id command created_ms]}]
            (array-map "id" id "command" (command-of command) "created_ms" created_ms))
          (api/query ctx ["SELECT id, command, created_ms FROM ui_inbox WHERE client = ? AND acked_ms IS NULL ORDER BY id"
                          client]))
    []))

(defn unacked-json
  "`unacked` as the JSON text of the inbox resource (`[]` when empty)."
  [ctx client]
  (json/generate-string (unacked ctx client)))

(defn ack!
  "Mark command `id` of `client` acknowledged and announce the change.
   Returns true when the row exists for that client, false when there is no
   such row. Acknowledging again is idempotent: the row keeps its first
   acknowledgement time, nothing is announced, the reply is still true."
  [ctx client id]
  (let [reply (api/submit-tx! ctx [["UPDATE ui_inbox SET acked_ms = ? WHERE id = ? AND client = ? AND acked_ms IS NULL"
                                    (now) id client]])]
    (when-not (:ok reply)
      (throw (refused reply "acknowledging a ui command")))
    (if (= 1 (get-in reply [:result :rows-affected]))
      (do (notify-updated! ctx client) true)
      (boolean (seq (api/query ctx ["SELECT id FROM ui_inbox WHERE id = ? AND client = ?" id client]))))))
