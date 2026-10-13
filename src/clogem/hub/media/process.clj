;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.media.process
  "The process runner behind clogem.api/run-process! (PD-5, ADR-0006).

   ffmpeg, ffprobe and whisper-cli run only as child processes started from
   an argv vector, never from a shell string. The program named first is
   resolved from the configured `:bins` map, else from PATH with
   babashka.fs/which (which tries .com .exe .bat .cmd on Windows). stdout
   and stderr are drained on their own threads and capped at
   `:max-output-bytes`; the caller's thread polls the deadline and the
   `:cancel` atom every `:poll-ms`. A kill is `destroy-tree` (SIGTERM on
   POSIX, TerminateProcess on Windows) followed by `destroyForcibly`, so
   partial outputs must be temp files the caller removes. With
   `:on-progress`, stdout is read line by line as the `-progress pipe:1`
   blocks ffmpeg writes (`key=value` lines, a block ending at `progress=`)
   and each complete block is delivered as it arrives instead of being
   accumulated into :out.

   The reply is the clogem.api contract:
   {:exit int-or-nil :out string :err string :duration-ms int
    :timed-out? bool :cancelled? bool :truncated? bool :argv [...]}
   where :exit is nil after a timeout or a cancel (the code a killed
   process reports means nothing: 143 or 137 on POSIX, 1 on Windows, which
   is also ffmpeg's ordinary failure code) and :argv is the resolved argv.
   Throws ex-info {:type :process/invalid-argv} (also for an option of the
   wrong shape, named in :option), {:type :process/binary-missing :binary
   name} and, when the operating system refuses to start the program (a
   missing working directory, a non-executable file), {:type
   :process/start-failed :binary name}.

   Sources (PD-11, read 2026-10-12): https://ffmpeg.org/ffmpeg.html
   (-progress: key=value lines, the last key of a block is progress with
   continue or end), https://ffmpeg.org/ffprobe.html (-print_format json,
   -show_format, -show_streams, -show_error; a positive exit code when
   the input cannot be opened), https://github.com/babashka/process
   (README.md, API.md: :dir :extra-env :in, streams as the ProcessBuilder
   default, destroy, destroy-tree, alive?, deref) and
   https://github.com/babashka/fs (API.md: which, :win-exts)."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clogem.hub.config :as config]
            [clogem.hub.log :as log])
  (:import [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Binaries

(defn resolve-binary
  "The executable to run for `name`: the path configured in `bins` (a map
   of program name → path) wins, else the first match on PATH. Either
   candidate goes through babashka.fs/which, which adds .com .exe .bat .cmd
   on Windows and returns nil for a path that is not an executable file.
   A configured path that does not resolve is nil too: the configuration
   is wrong and PATH must not paper over it. Returns a string or nil."
  [name bins]
  (when (and (string? name) (not (str/blank? name)))
    (some-> (fs/which (or (get bins name) name)) str)))

(defn binary-available?
  "True when `resolve-binary` finds `name`."
  [name bins]
  (some? (resolve-binary name bins)))

;; ---------------------------------------------------------------------------
;; Progress parsing (ffmpeg -progress pipe:1)

(def ^:private progress-line-re #"^([A-Za-z0-9_]+)=(.*)$")

(defn parse-progress-line
  "`[key value]` for one `key=value` line of ffmpeg's -progress output, the
   key a keyword (:frame, :out_time_us, :speed, :progress …) and the value
   a trimmed string (`bitrate=   1.1kbits/s` → \"1.1kbits/s\",
   `speed=1.5x` → \"1.5x\"); nil for a blank line or anything else."
  [line]
  (when (string? line)
    (when-let [[_ k v] (re-matches progress-line-re (str/trim line))]
      [(keyword k) (str/trim v)])))

(defn- progress-step
  "Feed `line` into the block under construction. Returns
   [next-block completed-block-or-nil]: a block completes at the key
   `progress` (its value \"continue\" or \"end\"), which ffmpeg always
   writes last; lines that are not key=value pairs are ignored."
  [block line]
  (if-let [[k v] (parse-progress-line line)]
    (let [block (assoc block k v)]
      (if (= :progress k) [{} block] [block nil]))
    [block nil]))

(defn progress-blocks
  "The complete progress blocks in `lines` (a seq of strings), in order:
   maps of keyword → string, each ending at a `progress=` line. A trailing
   block without its `progress=` line (a killed ffmpeg) is dropped."
  [lines]
  (-> (reduce (fn [[block blocks] line]
                (let [[block done] (progress-step block line)]
                  [block (if done (conj blocks done) blocks)]))
              [{} []]
              lines)
      second))

;; ---------------------------------------------------------------------------
;; Stream readers (each on its own thread)

(defn- drain-capped
  "Read `in` to EOF, keeping its first `cap` bytes. Returns
   {:text string :truncated? bool}; the stream being closed under the
   reader counts as EOF."
  [^java.io.InputStream in cap]
  (let [buf (byte-array 8192)
        kept (java.io.ByteArrayOutputStream.)]
    (loop [truncated? false]
      (let [n (try (.read in buf) (catch java.io.IOException _ -1))]
        (if (neg? n)
          {:text (String. (.toByteArray kept) "UTF-8") :truncated? truncated?}
          (let [take-n (min n (max 0 (- cap (.size kept))))]
            (when (pos? take-n) (.write kept buf 0 take-n))
            (recur (or truncated? (> n take-n)))))))))

(defn- deliver-progress! [on-progress block program]
  (try (on-progress block)
       (catch Exception e
         (log/warn {:event :process/on-progress-failed :program program :error (ex-message e)}))))

(defn- read-progress
  "Read `in` line by line, delivering every complete progress block to
   `on-progress` on this thread. Returns {:text \"\" :truncated? false}:
   nothing is accumulated."
  [^java.io.InputStream in on-progress program]
  (with-open [rdr (io/reader in :encoding "UTF-8")]
    (loop [block {}]
      (when-let [line (try (.readLine ^java.io.BufferedReader rdr) (catch java.io.IOException _ nil))]
        (let [[block done] (progress-step block line)]
          (when done (deliver-progress! on-progress done program))
          (recur block)))))
  {:text "" :truncated? false})

(defn- join-reader
  "The reader's result, closing the stream to unblock it when it has not
   finished `grace-ms` after the process ended (a surviving grandchild may
   still hold the pipe open)."
  [reader ^java.io.InputStream stream grace-ms]
  (let [r (deref reader grace-ms ::late)]
    (if (= ::late r)
      (do (try (.close stream) (catch Exception _ nil))
          (deref reader grace-ms {:text "" :truncated? true}))
      r)))

;; ---------------------------------------------------------------------------
;; Running

(def kill-grace-ms
  "How long a destroyed process may take to end before it is killed
   forcibly, and how long the forcible kill is given to take effect."
  500)

(def reader-grace-ms
  "How long the output readers may run on after the process ended."
  2000)

(defn- invalid-argv! [argv reason]
  (throw (ex-info (str "run-process!: " reason)
                  {:type :process/invalid-argv :reason reason
                   :argv (if (sequential? argv) (mapv pr-str argv) (pr-str argv))})))

(defn- check-argv!
  "`argv` as a vector of strings, or ex-info :process/invalid-argv."
  [argv]
  (cond
    (string? argv) (invalid-argv! argv "argv must be a vector of strings, never a shell string")
    (not (sequential? argv)) (invalid-argv! argv "argv must be a vector of strings")
    (empty? argv) (invalid-argv! argv "argv must not be empty")
    (not (every? string? argv)) (invalid-argv! argv "every argv element must be a string")
    (str/blank? (first argv)) (invalid-argv! argv "the program name must not be blank")
    :else (vec argv)))

(defn- check-opts! [{:keys [timeout-ms poll-ms max-output-bytes cancel on-progress env cwd stdin]}]
  (doseq [[k v] {:timeout-ms timeout-ms :poll-ms poll-ms :max-output-bytes max-output-bytes}]
    (when-not (and (integer? v) (pos? v))
      (throw (ex-info (str "run-process!: " (name k) " must be a positive integer, got " (pr-str v))
                      {:type :process/invalid-argv :option k :value v}))))
  (when-not (or (nil? cancel) (instance? clojure.lang.IAtom cancel))
    (throw (ex-info "run-process!: :cancel must be an atom" {:type :process/invalid-argv :option :cancel})))
  (when-not (or (nil? on-progress) (fn? on-progress))
    (throw (ex-info "run-process!: :on-progress must be a function" {:type :process/invalid-argv :option :on-progress})))
  (when-not (or (nil? env) (map? env))
    (throw (ex-info "run-process!: :env must be a map of strings" {:type :process/invalid-argv :option :env})))
  (when-not (or (nil? cwd) (string? cwd))
    (throw (ex-info "run-process!: :cwd must be a string" {:type :process/invalid-argv :option :cwd})))
  (when-not (or (nil? stdin) (string? stdin))
    (throw (ex-info "run-process!: :stdin must be a string" {:type :process/invalid-argv :option :stdin}))))

(defn- start!
  "Start the process, or ex-info :process/start-failed."
  [argv {:keys [cwd env stdin]}]
  (try
    (p/process (cond-> {:cmd argv}
                 cwd (assoc :dir cwd)
                 (seq env) (assoc :extra-env (into {} (map (fn [[k v]] [(str k) (str v)])) env))
                 stdin (assoc :in stdin)))
    (catch Exception e
      (throw (ex-info (str "run-process!: cannot start " (first argv) ": " (ex-message e))
                      {:type :process/start-failed :binary (first argv) :argv argv :cause (ex-message e)}
                      e)))))

(defn- kill!
  "destroy-tree, then destroyForcibly on the process and its descendants
   when it is still alive after `kill-grace-ms` (ADR-0006: a hard kill is
   the contract)."
  [child ^java.lang.Process proc]
  (p/destroy-tree child)
  (when-not (.waitFor proc kill-grace-ms TimeUnit/MILLISECONDS)
    (let [handle (.toHandle proc)]
      (doseq [^java.lang.ProcessHandle h (cons handle (iterator-seq (.iterator (.descendants handle))))]
        (try (.destroyForcibly h) (catch Exception _ nil))))
    (.waitFor proc kill-grace-ms TimeUnit/MILLISECONDS)))

(defn- await-outcome
  "Block until the process exits, the cancel atom turns true or the
   deadline passes, waking every `poll-ms`. Returns :exited, :cancelled or
   :timed-out."
  [^java.lang.Process proc deadline poll-ms cancel]
  (loop []
    (let [remaining (- deadline (System/currentTimeMillis))
          wait-ms (max 1 (min poll-ms remaining))]
      (cond
        (.waitFor proc wait-ms TimeUnit/MILLISECONDS) :exited
        (and cancel @cancel) :cancelled
        (>= (System/currentTimeMillis) deadline) :timed-out
        :else (recur)))))

(defn run!
  "Run `argv` (a vector of strings, program name first) as a child process
   and return the reply map of the clogem.api contract (see the namespace
   doc). `opts`:
     :timeout-ms       kill after this long (default 600000)
     :cwd              working directory
     :env              extra environment variables (map of strings)
     :stdin            a string written to the process, then closed; without
                       it the process's stdin is closed at once (EOF)
     :on-progress      (fn [block]) called on the reader thread for every
                       complete -progress block; stdout is then not kept
     :cancel           an atom; reset it to true to kill the process
     :max-output-bytes cap on :out and on :err, each (default 16 MiB)
     :poll-ms          cancel and deadline polling interval (default 100)
     :bins             program name → path, consulted before PATH
     :module           the calling module's id, for the log only"
  [argv opts]
  (let [opts (merge (:process config/defaults) opts)
        {:keys [timeout-ms poll-ms max-output-bytes cancel on-progress bins module]} opts
        argv (check-argv! argv)
        _ (check-opts! opts)
        program (first argv)
        path (or (resolve-binary program bins)
                 (throw (ex-info (str "run-process!: " program " is not installed (not in :bins, not on PATH)")
                                 {:type :process/binary-missing :binary program})))
        argv (assoc argv 0 path)
        started (System/currentTimeMillis)
        deadline (+ started timeout-ms)
        child (start! argv opts)
        ^java.lang.Process proc (:proc child)
        ^java.io.InputStream stdout (:out child)
        ^java.io.InputStream stderr (:err child)]
    (when-not (:stdin opts)
      (try (.close ^java.io.OutputStream (:in child)) (catch Exception _ nil)))
    (log/debug {:event :process/started :program program :module module :pid (try (.pid proc) (catch Exception _ nil))})
    (let [out-reader (future (if on-progress
                               (read-progress stdout on-progress program)
                               (drain-capped stdout max-output-bytes)))
          err-reader (future (drain-capped stderr max-output-bytes))
          outcome (await-outcome proc deadline poll-ms cancel)
          _ (when-not (= :exited outcome) (kill! child proc))
          out (join-reader out-reader stdout reader-grace-ms)
          err (join-reader err-reader stderr reader-grace-ms)
          exit (when (and (= :exited outcome) (not (.isAlive proc))) (.exitValue proc))
          reply {:exit exit
                 :out (:text out)
                 :err (:text err)
                 :duration-ms (- (System/currentTimeMillis) started)
                 :timed-out? (= :timed-out outcome)
                 :cancelled? (= :cancelled outcome)
                 :truncated? (boolean (or (:truncated? out) (:truncated? err)))
                 :argv argv}
          summary (-> reply (dissoc :out :err) (assoc :program program :module module))]
      (if (and (= 0 exit) (not (:truncated? reply)))
        (log/debug (assoc summary :event :process/finished))
        (log/warn (assoc summary :event :process/finished
                         :err-head (let [e (str/trim (:err reply))] (subs e 0 (min 1000 (count e)))))))
      reply)))

;; ---------------------------------------------------------------------------
;; ffprobe

(defn ffprobe-argv
  "The argv that probes `path`: JSON on stdout, errors as an `error`
   object in the same document and on stderr."
  [path]
  ["ffprobe" "-v" "error" "-print_format" "json" "-show_format" "-show_streams" "-show_error" (str path)])

(defn- seconds->ms
  "Integer milliseconds from ffprobe's seconds string (\"95.400000\"), or
   nil when it is absent or not a number."
  [s]
  (when (string? s)
    (try (Math/round (* 1000.0 (Double/parseDouble s)))
         (catch Exception _ nil))))

(defn- parse-json [s]
  (when (and (string? s) (not (str/blank? s)))
    (try (let [v (json/parse-string s true)] (when (map? v) v))
         (catch Exception _ nil))))

(defn ffprobe-reply
  "Interpret a run! reply of `ffprobe-argv`:
   {:ok true :format {} :streams [] :duration-ms n} on exit 0 with a JSON
   document, :duration-ms from format.duration (a seconds string, rounded
   to integer milliseconds), else the longest stream duration, else nil;
   {:ok false :exit n :error e} otherwise, `e` being ffprobe's error object
   {:code int :string text} when -show_error printed one, else the trimmed
   stderr (or what went wrong with the run)."
  [{:keys [exit out err timed-out? cancelled?] :as reply}]
  (let [doc (parse-json out)]
    (cond
      timed-out? {:ok false :exit nil :error "ffprobe timed out" :timed-out? true}
      cancelled? {:ok false :exit nil :error "ffprobe was cancelled" :cancelled? true}
      (and (= 0 exit) doc (not (:error doc)))
      (let [streams (vec (:streams doc))
            format (or (:format doc) {})]
        {:ok true
         :format format
         :streams streams
         :duration-ms (or (seconds->ms (:duration format))
                          (some->> streams (keep (comp seconds->ms :duration)) seq (apply max)))})
      (:error doc) {:ok false :exit exit :error (:error doc)}
      :else {:ok false :exit exit
             :error (let [e (str/trim (str err))]
                      (cond
                        (not (str/blank? e)) e
                        (= 0 exit) (str "ffprobe printed no JSON document: " (pr-str (subs (str out) 0 (min 200 (count (str out))))))
                        :else (str "ffprobe exited with " (pr-str exit) (when (:truncated? reply) " (output truncated)"))))})))

(defn ffprobe!
  "Probe the media file at `path` with ffprobe through run! (`opts` as for
   run!: :bins, :timeout-ms …) and return `ffprobe-reply`. Throws what run!
   throws (:process/binary-missing when ffprobe is not installed)."
  [path opts]
  (ffprobe-reply (run! (ffprobe-argv path) opts)))

;; ---------------------------------------------------------------------------
;; The runtime seam

(defn runner
  "The runtime's `:process` function, (fn [ctx argv opts] reply), for the
   hub `config`: the `:process` defaults (:timeout-ms :max-output-bytes
   :poll-ms) sit under the caller's opts, the configured `:bins` win over
   any the caller passes (the user's configuration decides where ffmpeg
   lives; a module may add programs the configuration does not name), and
   the calling module's id goes to the log."
  [config]
  (let [defaults (merge (:process config/defaults) (:process config))
        bins (:bins config)]
    (fn [ctx argv opts]
      (let [opts (or opts {})]
        (run! argv (merge defaults opts {:bins (merge (:bins opts) bins)
                                         :module (:module/id ctx)}))))))
