;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.lock
  "The single-instance lock: one clogem-hub daemon per OS user (PD-1).

   `daemon.lock` in the runtime directory is locked with an OS file lock
   (fcntl through java.nio) for the daemon's lifetime; a second daemon's
   `tryLock` fails and it refuses to start. The kernel releases the lock when
   the holder dies, however it dies, so a stale lock file is never a problem
   and the file is left in place. The holder's pid is written into the file
   for diagnostics only; `daemon.edn` remains the record other doors read.

   POSIX pitfall honoured here: closing any descriptor of a file releases
   every fcntl lock this process holds on it. So the handles this process
   holds are remembered in `held`, and neither `acquire!` nor `held?` ever
   opens a second descriptor on a file this process already locks."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defonce ^{:doc "Absolute lock path → handle, for the locks this process holds."}
  held (atom {}))

(defn lock-path
  "Where the lock lives for a runtime directory."
  [dir]
  (str (fs/path dir "daemon.lock")))

(defn holder-pid
  "The pid written by the current or last holder, or nil. When this process
   holds the lock the pid comes from memory: reading the file would open and
   close a descriptor, which on POSIX releases this process's own lock."
  [path]
  (if-let [h (get @held (str (fs/absolutize (str path))))]
    (:pid h)
    (try (some-> (slurp (str path)) str/trim not-empty parse-long)
         (catch Exception _ nil))))

(defn- try-lock
  "The FileLock, or nil when another process (or this one) holds it."
  [ch]
  (try (.tryLock ch)
       (catch Exception e
         ;; the same JVM already holds an overlapping lock (a second start!
         ;; in one process): that is "held", not an error
         (if (= "java.nio.channels.OverlappingFileLockException" (.getName (class e)))
           nil
           (throw e)))))

(defn- canonical [dir] (str (fs/absolutize (lock-path dir))))

(defn- refuse [path]
  (let [holder (holder-pid path)]
    (throw (ex-info (str "another clogem-hub daemon is running (lock " path
                         (when holder (str ", pid " holder)) ")")
                    {:type :already-running :lock path :holder holder}))))

(defn acquire!
  "Lock `dir`'s daemon.lock for this process. Returns the handle `release!`
   takes. Throws ex-info {:type :already-running :lock path :holder pid}
   when another daemon (or this process already) holds it."
  [dir]
  (fs/create-dirs dir)
  (let [path (canonical dir)]
    (when (contains? @held path)
      (refuse path))
    (let [raf (java.io.RandomAccessFile. path "rw")
          ch (.getChannel raf)
          lock (try-lock ch)]
      (if lock
        (let [handle {:path path :file raf :channel ch :lock lock
                      :pid (.pid (java.lang.ProcessHandle/current))}]
          (.setLength raf 0)
          (.write ch (java.nio.ByteBuffer/wrap
                      (.getBytes (str (.pid (java.lang.ProcessHandle/current)) "\n") "UTF-8")))
          (.force ch false)
          (swap! held assoc path handle)
          handle)
        (do (.close raf)
            (refuse path))))))

(defn release!
  "Release a handle from `acquire!`: closing the channel releases every lock
   held through it (java.nio.channels.FileLock itself is not reachable from
   Babashka code, so the lock object is never touched). Idempotent; nil-safe."
  [{:keys [path channel file]}]
  (when path (swap! held dissoc path))
  (when channel (try (.close channel) (catch Exception _ nil)))
  (when file (try (.close file) (catch Exception _ nil)))
  nil)

(defn held?
  "True when some process currently holds `dir`'s lock: this one (remembered,
   never probed, see the namespace doc) or another (probe: lock and release
   at once when free)."
  [dir]
  (let [path (canonical dir)]
    (cond
      (contains? @held path) true
      (not (fs/exists? path)) false
      :else
      (let [raf (java.io.RandomAccessFile. path "rw")
            ch (.getChannel raf)
            lock (try-lock ch)]
        (try (nil? lock)
             (finally (.close ch) (.close raf)))))))
