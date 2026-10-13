;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.fingerprint
  "Content identity of a media file, pure functions over the file's bytes.

   `fingerprint` is the lower-case hex SHA-256 of three parts, in order:
   the file size as an 8-byte big-endian integer, the first
   min(size, 1 MiB) bytes and the last min(size, 1 MiB) bytes. For a file
   of 2 MiB or more the two windows are disjoint; for a shorter file they
   overlap (a 1.5 MiB file hashes bytes [0, 1 MiB) then [0.5 MiB, 1.5 MiB)),
   and a file shorter than 1 MiB hashes its whole content twice. It reads
   at most 2 MiB however large the file is, which is what makes
   registering a feature film cheap; the price is that a change confined
   to the middle of a file larger than 2 MiB that keeps its size is not
   seen. Moving or renaming a file never changes its fingerprint.

   `media-id` derives the stable id a row is keyed by: \"m-\" followed by
   the first 32 hex characters of sha256(fingerprint \"|\" duration-ms),
   the duration being the integer milliseconds ffprobe reports. The id is
   a function of content alone, so the same file registered from two
   places, or after a move, is the same item."
  (:require [babashka.fs :as fs])
  (:import [java.io RandomAccessFile]
           [java.nio ByteBuffer]
           [java.security MessageDigest]))

(def window-bytes
  "How many bytes of the head and of the tail of a file enter the fingerprint."
  1048576)

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn- size-bytes
  "`size` as an 8-byte big-endian integer."
  ^bytes [size]
  (.array (doto (ByteBuffer/allocate 8) (.putLong (long size)))))

(defn- read-window
  "`n` bytes of `raf` from `offset`."
  ^bytes [^RandomAccessFile raf offset n]
  (let [buf (byte-array n)]
    (.seek raf (long offset))
    (.readFully raf buf)
    buf))

(defn fingerprint
  "The content fingerprint of the regular file at `path` (see the
   namespace doc). Throws ex-info {:type :media/not-found} when `path` is
   not a regular file."
  [path]
  (when-not (fs/regular-file? path)
    (throw (ex-info (str "fingerprint: not a regular file: " path)
                    {:type :media/not-found :path (str path)})))
  (let [size (fs/size path)
        n (min size window-bytes)
        md (MessageDigest/getInstance "SHA-256")]
    (with-open [raf (RandomAccessFile. (fs/file path) "r")]
      (.update md (size-bytes size))
      (.update md (read-window raf 0 n))
      (.update md (read-window raf (- size n) n)))
    (hex (.digest md))))

(defn media-id
  "The media id for `fingerprint` and the integer `duration-ms`:
   \"m-\" plus the first 32 hex characters of sha256(fingerprint \"|\" duration-ms)."
  [fingerprint duration-ms]
  (let [md (MessageDigest/getInstance "SHA-256")
        text (str fingerprint "|" (long duration-ms))]
    (str "m-" (subs (hex (.digest md (.getBytes text "UTF-8"))) 0 32))))
