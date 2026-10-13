;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.probe
  "ffprobe for the `media` module, through clogem.api/run-process! only
   (PD-5: the hub starts the child process from an argv vector; this
   namespace never touches a process or a shell itself).

   `argv` is the documented command, `ffprobe -v error -print_format json
   -show_format -show_streams -show_error <path>`: stdout carries one JSON
   document holding `format` and `streams`, or an `error` object
   ({\"code\": int, \"string\": text}) when the input cannot be opened
   (https://ffmpeg.org/ffprobe.html; the hub's process runner records the
   PD-11 read). `parse` turns that document into
   {:format map :streams vector :duration-ms int-or-nil}: `format.duration`
   is a seconds string, rounded to integer milliseconds, with the longest
   stream duration as the fallback and nil when nothing carries one.
   `parse` throws ex-info {:type :media/probe-failed} for an error object
   (:error holds it) and for a document that is not JSON (:reason
   :invalid-json); `probe!` runs the command and adds the exit code, the
   trimmed stderr and the path to a failure, which also covers a timeout
   and a cancellation. What run-process! itself throws
   (:process/binary-missing when ffprobe is not installed) propagates as
   is."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clogem.api :as api]))

(def default-timeout-ms
  "How long one ffprobe run may take unless the module configuration
   (`:probe-timeout-ms`) says otherwise."
  60000)

(defn argv
  "The argv that probes `path`."
  [path]
  ["ffprobe" "-v" "error" "-print_format" "json" "-show_format" "-show_streams" "-show_error" (str path)])

(defn seconds->ms
  "Integer milliseconds from ffprobe's seconds (a string such as
   \"95.400000\", or a number); nil when absent or not a finite number."
  [s]
  (let [d (cond
            (number? s) (double s)
            (string? s) (try (Double/parseDouble s) (catch Exception _ nil))
            :else nil)]
    (when (and d (not (.isNaN ^Double d)) (not (.isInfinite ^Double d)))
      (Math/round (* 1000.0 d)))))

(defn- head [s]
  (let [s (str s)] (subs s 0 (min 200 (count s)))))

(defn- fail! [message data]
  (throw (ex-info (str "ffprobe failed: " message) (merge {:type :media/probe-failed} data))))

(defn parse
  "{:format :streams :duration-ms} from ffprobe's JSON document `out`, or
   ex-info :media/probe-failed (see the namespace doc)."
  [out]
  (let [doc (when (string? out)
              (try (json/parse-string out true) (catch Exception _ nil)))]
    (cond
      (not (map? doc))
      (fail! "no JSON document on stdout" {:reason :invalid-json :out-head (head out)})

      (:error doc)
      (let [e (:error doc)]
        (fail! (or (:string e) (pr-str e)) {:reason :ffprobe-error :error e}))

      :else
      (let [streams (vec (:streams doc))
            format (or (:format doc) {})]
        {:format format
         :streams streams
         :duration-ms (or (seconds->ms (:duration format))
                          (some->> streams (keep (comp seconds->ms :duration)) seq (apply max)))}))))

(defn- parse-or-fail
  "`parse`, with the run's `context` merged into a failure; a document that
   is not JSON is reported with stderr when ffprobe wrote some."
  [out {:keys [stderr] :as context}]
  (try (parse out)
       (catch clojure.lang.ExceptionInfo e
         (let [data (ex-data e)]
           (if (= :media/probe-failed (:type data))
             (throw (ex-info (if (and (= :invalid-json (:reason data)) (not (str/blank? stderr)))
                               (str "ffprobe failed: " stderr)
                               (ex-message e))
                             (merge data context)
                             e))
             (throw e))))))

(defn probe!
  "Run ffprobe on `path` through clogem.api/run-process! and return
   {:format :streams :duration-ms}; ex-info :media/probe-failed otherwise."
  [ctx path]
  (let [timeout-ms (or (api/config ctx [:probe-timeout-ms]) default-timeout-ms)
        {:keys [exit out err timed-out? cancelled?]} (api/run-process! ctx (argv path) {:timeout-ms timeout-ms})
        stderr (str/trim (str err))
        context {:path (str path) :exit exit :stderr stderr}]
    (cond
      timed-out? (fail! (str "timed out after " timeout-ms " ms") (assoc context :timed-out? true))
      cancelled? (fail! "cancelled" (assoc context :cancelled? true))
      :else
      (let [result (parse-or-fail out context)]
        (if (= 0 exit)
          result
          (fail! (if (str/blank? stderr) (str "exit code " (pr-str exit)) stderr) context))))))
