;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.fingerprint-test
  "The content fingerprint over generated files of known content: the
   expected digest is computed here from the in-memory bytes, independently
   of the implementation's file reading."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clogem.module.media.fingerprint :as fingerprint])
  (:import [java.nio ByteBuffer]
           [java.security MessageDigest]
           [java.util Arrays Random]))

(def mib 1048576)

(defn- content
  "`n` deterministic pseudo-random bytes for `seed`."
  ^bytes [n seed]
  (let [b (byte-array n)]
    (.nextBytes (Random. (long seed)) b)
    b))

(defn- sha256-hex [& chunks]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [^bytes c chunks] (.update md c))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- be8 ^bytes [n]
  (.array (doto (ByteBuffer/allocate 8) (.putLong (long n)))))

(defn- expected
  "size ‖ first min(size, 1 MiB) ‖ last min(size, 1 MiB), from the bytes."
  [^bytes bs]
  (let [size (alength bs)
        n (min size mib)]
    (sha256-hex (be8 size) (Arrays/copyOfRange bs 0 (int n)) (Arrays/copyOfRange bs (int (- size n)) (int size)))))

(defn- with-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-fingerprint"})]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(defn- write! [dir name ^bytes bs]
  (let [p (str (fs/path dir name))]
    (fs/write-bytes p bs)
    p))

(defn- flip-byte! [path offset]
  (let [^bytes bs (fs/read-all-bytes path)]
    (aset-byte bs offset (unchecked-byte (bit-xor (aget bs offset) 0x5a)))
    (fs/write-bytes path bs)))

(deftest fingerprints-of-known-content
  (with-dir
    (fn [dir]
      (let [sizes {"empty.bin" 0 "small.bin" 100 "one-and-a-half.bin" (+ mib (/ mib 2)) "two-and-a-half.bin" (+ (* 2 mib) (/ mib 2))}
            files (into {} (for [[name size] sizes] [name (content (long size) (count name))]))
            prints (into {} (for [[name bs] files] [name (fingerprint/fingerprint (write! dir name bs))]))]
        (doseq [[name bs] files]
          (testing name
            (is (= (expected bs) (get prints name)))
            (is (re-matches #"[0-9a-f]{64}" (get prints name)))))
        (testing "a 0-byte file hashes its size alone"
          (is (= (sha256-hex (be8 0)) (get prints "empty.bin"))))
        (testing "a file shorter than 1 MiB hashes its whole content twice"
          (let [bs (get files "small.bin")]
            (is (= (sha256-hex (be8 100) bs bs) (get prints "small.bin")))))
        (testing "a 1.5 MiB file's windows overlap by half a MiB"
          (let [^bytes bs (get files "one-and-a-half.bin")
                size (alength bs)]
            (is (= (sha256-hex (be8 size) (Arrays/copyOfRange bs 0 mib) (Arrays/copyOfRange bs (- size mib) size))
                   (get prints "one-and-a-half.bin")))))
        (is (= 4 (count (set (vals prints)))) "distinct content, distinct fingerprints")
        (testing "same content under another name has the same fingerprint"
          (let [copy (write! dir "copy.bin" (get files "small.bin"))]
            (is (= (get prints "small.bin") (fingerprint/fingerprint copy)))))))))

(deftest moving-keeps-and-editing-changes-the-fingerprint
  (with-dir
    (fn [dir]
      (let [bs (content (+ (* 2 mib) (/ mib 2)) 7)
            p (write! dir "clip.bin" bs)
            fp (fingerprint/fingerprint p)]
        (testing "a moved file keeps its fingerprint"
          (let [moved (str (fs/path dir "sub" "renamed.bin"))]
            (fs/create-dirs (fs/parent moved))
            (fs/move p moved)
            (is (= fp (fingerprint/fingerprint moved)))
            (fs/move moved p)))
        (testing "a byte changed in the first MiB changes it"
          (flip-byte! p 4096)
          (is (not= fp (fingerprint/fingerprint p)))
          (flip-byte! p 4096)
          (is (= fp (fingerprint/fingerprint p)) "and back"))
        (testing "a byte changed in the last MiB changes it"
          (flip-byte! p (- (alength bs) 10))
          (is (not= fp (fingerprint/fingerprint p)))
          (flip-byte! p (- (alength bs) 10)))
        (testing "a byte changed between the two windows of a file over 2 MiB is not seen (documented)"
          (flip-byte! p (+ mib (/ mib 4)))
          (is (= fp (fingerprint/fingerprint p))))
        (testing "a change of size changes it"
          (fs/write-bytes p (content (inc (alength bs)) 7))
          (is (not= fp (fingerprint/fingerprint p))))))))

(deftest fingerprint-refuses-what-is-not-a-regular-file
  (with-dir
    (fn [dir]
      (doseq [p [(str dir) (str (fs/path dir "missing.bin"))]]
        (let [data (try (fingerprint/fingerprint p) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :media/not-found (:type data)) p)
          (is (= p (:path data))))))))

(deftest media-id-is-a-function-of-fingerprint-and-duration
  (let [fp (apply str (repeat 64 "a"))
        id (fingerprint/media-id fp 95400)]
    (is (re-matches #"m-[0-9a-f]{32}" id))
    (is (= (str "m-" (subs (sha256-hex (.getBytes (str fp "|95400") "UTF-8")) 0 32)) id)
        "\"m-\" plus the first 32 hex characters of sha256(fingerprint \"|\" duration-ms)")
    (is (= id (fingerprint/media-id fp 95400)) "deterministic")
    (is (not= id (fingerprint/media-id fp 95401)) "another duration, another id")
    (is (not= id (fingerprint/media-id (str "b" (subs fp 1)) 95400)) "another fingerprint, another id")))
