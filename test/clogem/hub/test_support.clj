;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.test-support
  "Helpers shared by the hub's tests."
  (:require [babashka.fs :as fs]))

(defn delete-tree!
  "Delete a temporary tree, tolerating what a just-stopped daemon still does
   to it: the SQLite pod removes the WAL file when its last connection
   closes, a moment after the writer reported the stop, so a walk can meet
   a file that vanishes under it (NoSuchFileException), and on Windows a
   file may be held open for a moment longer, and read-only files (git
   objects) need `:force`. Retries for up to two seconds before giving up
   with the last error."
  [dir]
  (loop [attempt 0]
    (let [result (try (fs/delete-tree dir {:force true}) ::done
                      (catch Exception e
                        (if (and (< attempt 20) (fs/exists? dir))
                          (do (Thread/sleep 100) ::retry)
                          (if (fs/exists? dir) (throw e) ::done))))]
      (when (= ::retry result) (recur (inc attempt))))))
