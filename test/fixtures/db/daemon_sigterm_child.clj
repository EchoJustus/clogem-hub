;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

;; Test fixture, run as a child process by clogem.hub.daemon-test:
;;   bb test/fixtures/db/daemon_sigterm_child.clj <dir> <n>
;; Starts the daemon like -main does (signal handlers included), queues n
;; slow transactions, prints READY and waits to be terminated. The test
;; sends SIGTERM and checks that every queued transaction was committed.
(require '[clogem.hub.config :as config]
         '[clogem.hub.daemon :as daemon]
         '[clogem.hub.db.store :as store]
         '[clogem.hub.log :as log])

(log/set-level! :info)
(let [[dir n] *command-line-args*
      config (config/load-config {:http {:port 0} :db {:path (str dir "/clogem.db")}})
      system (daemon/start! {:config config :runtime-dir (str dir "/run")})
      st (:store system)]
  (daemon/install-signal-handlers! system)
  (store/tx! st :hub ["CREATE TABLE IF NOT EXISTS sig_t (id INTEGER PRIMARY KEY, v INTEGER)"])
  (dotimes [_ (Long/parseLong n)]
    (future (store/tx! st :hub ["INSERT INTO sig_t (v) WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 200000) SELECT x FROM c"])))
  (Thread/sleep 300)
  (println "READY")
  (flush)
  @(promise))
