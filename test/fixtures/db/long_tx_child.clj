;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

;; Test fixture, run as a child process by clogem.hub.db.store-test:
;;   bb test/fixtures/db/long_tx_child.clj <db-path> <rows>
;; Opens the store, commits five rows, prints READY, then starts one huge
;; transaction. The test kills this process (and its pod) while that
;; transaction runs and checks that the database stays consistent.
(require '[clogem.hub.bus :as bus]
         '[clogem.hub.db.store :as store]
         '[clogem.hub.log :as log])

(log/set-level! :error)
(let [[db rows] *command-line-args*
      b (bus/new-bus)
      s (store/open! {:path db :bus b})]
  (store/tx! s :hub ["CREATE TABLE IF NOT EXISTS kill_t (id INTEGER PRIMARY KEY, v INTEGER)"])
  (store/tx! s :hub (vec (repeat 5 "INSERT INTO kill_t (v) VALUES (0)")))
  (println "READY")
  (flush)
  (let [reply (store/tx! s :hub [["INSERT INTO kill_t (v) WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < ?) SELECT x FROM c"
                                  (Long/parseLong rows)]])]
    (println "DONE" (pr-str reply))
    (flush))
  (store/close! s)
  (bus/stop! b))
