;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.media.process-test
  "The process runner against two Babashka fixture scripts that emulate
   ffmpeg and ffprobe (test/fixtures/process), so no real tool is needed.
   Timing assertions are loose: CI runners are slow and every child is a
   fresh bb start."
  (:require [babashka.fs :as fs]
            [clogem.hub.test-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clogem.hub.log :as log]
            [clogem.hub.media.process :as process]))

(use-fixtures :once (fn [f] (log/set-level! :error) (try (f) (finally (log/set-level! :info)))))

(def bb-path
  "The Babashka that runs the fixtures, found the way the runner finds
   every program (PATH on both CI runners)."
  (process/resolve-binary "bb" {}))

;; Absolute, so that a child started with another :cwd still finds them.
(def fake-ffmpeg (str (fs/absolutize (fs/path "test" "fixtures" "process" "fake_ffmpeg.clj"))))
(def fake-ffprobe (str (fs/absolutize (fs/path "test" "fixtures" "process" "fake_ffprobe.clj"))))

(defn- ffmpeg-argv [& args] (into ["bb" fake-ffmpeg] args))

(defn- run-fake [opts & args] (process/run! (apply ffmpeg-argv args) opts))

(defn- ex-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- with-temp-dir [f]
  (let [dir (fs/create-temp-dir {:prefix "clogem-process"})]
    (try (f dir) (finally (support/delete-tree! dir)))))

;; ---------------------------------------------------------------------------
;; Binaries

(deftest resolve-binary-prefers-bins-then-path-and-reports-absence
  (is (string? bb-path) "bb must be on PATH for these tests")
  (testing "PATH"
    (is (= bb-path (process/resolve-binary "bb" {})))
    (is (str/includes? (str/lower-case (fs/file-name bb-path)) "bb"))
    (is (fs/absolute? bb-path)))
  (testing "the bins map wins over PATH"
    (is (= bb-path (process/resolve-binary "ffmpeg" {"ffmpeg" bb-path})))
    (is (true? (process/binary-available? "ffmpeg" {"ffmpeg" bb-path}))))
  (testing "a bins entry that does not resolve is not papered over by PATH"
    (with-temp-dir
      (fn [dir]
        (is (nil? (process/resolve-binary "bb" {"bb" (str (fs/path dir "no-such-bb"))}))))))
  (testing "missing"
    (is (nil? (process/resolve-binary "clogem-definitely-not-a-program-7f3a" {})))
    (is (false? (process/binary-available? "clogem-definitely-not-a-program-7f3a" {})))
    (is (nil? (process/resolve-binary "" {})))
    (is (nil? (process/resolve-binary nil {})))))

;; ---------------------------------------------------------------------------
;; run!: argv and binaries

(deftest run-rejects-bad-argv-and-missing-binaries
  (testing "a shell string, an empty vector and non-string elements are invalid argv"
    (is (= :process/invalid-argv (:type (ex-type #(process/run! "bb -e 1" {})))))
    (is (= :process/invalid-argv (:type (ex-type #(process/run! [] {})))))
    (is (= :process/invalid-argv (:type (ex-type #(process/run! ["bb" :e 1] {})))))
    (is (= :process/invalid-argv (:type (ex-type #(process/run! ["" "x"] {})))))
    (is (= :process/invalid-argv (:type (ex-type #(process/run! nil {}))))))
  (testing "bad options are invalid too"
    (is (= :process/invalid-argv (:type (ex-type #(process/run! ["bb"] {:timeout-ms 0})))))
    (is (= :process/invalid-argv (:type (ex-type #(process/run! ["bb"] {:cancel true}))))))
  (testing "a program neither in :bins nor on PATH"
    (let [data (ex-type #(process/run! ["clogem-definitely-not-a-program-7f3a" "x"] {}))]
      (is (= :process/binary-missing (:type data)))
      (is (= "clogem-definitely-not-a-program-7f3a" (:binary data)))))
  (testing "a program the operating system cannot start"
    (with-temp-dir
      (fn [dir]
        (let [data (ex-type #(process/run! ["bb" "-e" "1"] {:cwd (str (fs/path dir "missing-dir"))}))]
          (is (= :process/start-failed (:type data))))))))

;; ---------------------------------------------------------------------------
;; run!: the reply

(deftest run-captures-exit-code-stdout-and-stderr
  (let [reply (run-fake {} "--stdout" "hello out" "--stderr" "hello err" "--exit" "3")]
    (is (= 3 (:exit reply)))
    (is (str/includes? (:out reply) "hello out"))
    (is (str/includes? (:err reply) "hello err"))
    (is (not (str/includes? (:out reply) "hello err")) "stderr is not merged into stdout")
    (is (false? (:timed-out? reply)))
    (is (false? (:cancelled? reply)))
    (is (false? (:truncated? reply)))
    (is (integer? (:duration-ms reply)))
    (is (<= 0 (:duration-ms reply)))
    (testing ":argv is the resolved argv, the program replaced by its path"
      (is (= bb-path (first (:argv reply))))
      (is (= (rest (ffmpeg-argv "--stdout" "hello out" "--stderr" "hello err" "--exit" "3")) (rest (:argv reply)))))
    (is (= #{:exit :out :err :duration-ms :timed-out? :cancelled? :truncated? :argv} (set (keys reply))))))

(deftest run-exit-zero-and-empty-streams
  (let [reply (run-fake {})]
    (is (= 0 (:exit reply)))
    (is (= "" (:out reply)))
    (is (= "" (:err reply)))))

(deftest run-feeds-stdin-and-closes-it
  (testing "a :stdin string reaches the process"
    (let [reply (run-fake {:stdin "ping over stdin\n"} "--echo-stdin")]
      (is (= 0 (:exit reply)))
      (is (= "ping over stdin\n" (:out reply)))))
  (testing "without :stdin the process reads EOF instead of hanging"
    (let [reply (run-fake {:timeout-ms 60000} "--echo-stdin")]
      (is (= 0 (:exit reply)))
      (is (false? (:timed-out? reply)))
      (is (= "" (:out reply))))))

(deftest run-honours-cwd-and-env
  (with-temp-dir
    (fn [dir]
      (testing ":cwd is the child's working directory"
        (let [reply (run-fake {:cwd (str dir)} "--print-cwd")]
          (is (= 0 (:exit reply)))
          (is (fs/same-file? dir (str/trim (:out reply))) (pr-str (:out reply)))))
      (testing ":env adds variables without replacing the environment (PATH still finds bb)"
        (let [reply (run-fake {:env {"CLOGEM_PROCESS_TEST" "forty-two"}} "--print-env" "CLOGEM_PROCESS_TEST")]
          (is (= 0 (:exit reply)))
          (is (= "forty-two" (str/trim (:out reply))))))
      (testing "a variable that was not set reads as empty"
        (let [reply (run-fake {} "--print-env" "CLOGEM_PROCESS_TEST_UNSET")]
          (is (= "" (str/trim (:out reply)))))))))

(deftest run-truncates-output-at-max-output-bytes
  (let [reply (run-fake {:max-output-bytes 1000} "--stdout-bytes" "5000")]
    (is (= 0 (:exit reply)) "the process still runs to completion; the surplus is drained and dropped")
    (is (true? (:truncated? reply)))
    (is (= 1000 (count (:out reply))))
    (is (every? #{\x} (:out reply))))
  (testing "output within the cap is not truncated"
    (let [reply (run-fake {:max-output-bytes 1000} "--stdout-bytes" "999")]
      (is (false? (:truncated? reply)))
      (is (= 999 (count (:out reply))))))
  (testing "stderr is capped too"
    (let [reply (run-fake {:max-output-bytes 8} "--stderr" "0123456789abcdef")]
      (is (true? (:truncated? reply)))
      (is (= "01234567" (:err reply))))))

(deftest run-times-out-and-kills-the-process
  (let [started (System/currentTimeMillis)
        reply (run-fake {:timeout-ms 300 :poll-ms 20} "--forever")
        took (- (System/currentTimeMillis) started)]
    (is (true? (:timed-out? reply)))
    (is (nil? (:exit reply)))
    (is (false? (:cancelled? reply)))
    (is (<= 300 took) "the timeout is honoured, not cut short")
    (is (< took 30000) (str "the kill does not wait for the process to give up by itself (took " took " ms)"))
    (is (integer? (:duration-ms reply)))))

(deftest run-cancels-through-the-atom
  (let [cancel (atom false)
        _ (future (Thread/sleep 300) (reset! cancel true))
        started (System/currentTimeMillis)
        reply (run-fake {:timeout-ms 60000 :poll-ms 20 :cancel cancel} "--forever")
        took (- (System/currentTimeMillis) started)]
    (is (true? (:cancelled? reply)))
    (is (nil? (:exit reply)))
    (is (false? (:timed-out? reply)))
    (is (< took 30000) (str "cancel killed the process (took " took " ms)"))))

(deftest run-delivers-progress-blocks-as-they-arrive
  (let [blocks (atom [])
        arrivals (atom [])
        reply (run-fake {:on-progress (fn [b] (swap! blocks conj b) (swap! arrivals conj (System/currentTimeMillis)))}
                        "--blocks" "3" "--sleep-ms" "400")]
    (is (= 0 (:exit reply)))
    (is (= 3 (count @blocks)))
    (is (= "" (:out reply)) "stdout is not accumulated when :on-progress is given")
    (is (false? (:truncated? reply)))
    (testing "every block is a map of keyword → string with ffmpeg's keys"
      (doseq [b @blocks]
        (is (every? keyword? (keys b)))
        (is (every? string? (vals b)))
        (is (= #{:frame :fps :stream_0_0_q :bitrate :total_size :out_time_us :out_time_ms :out_time
                 :dup_frames :drop_frames :speed :progress}
               (set (keys b))))))
    (is (= ["continue" "continue" "end"] (mapv :progress @blocks)))
    (is (= ["500000" "1000000" "1500000"] (mapv :out_time_us @blocks)))
    (is (= "1.1kbits/s" (:bitrate (first @blocks))) "values are trimmed")
    (is (= "1.5x" (:speed (last @blocks))))
    (testing "blocks arrive while the process runs, not in one batch at the end"
      (let [gap (- (last @arrivals) (first @arrivals))]
        (is (<= 300 gap) (str "first and last block were " gap " ms apart"))))))

(deftest run-survives-a-failing-progress-callback
  (let [seen (atom 0)
        reply (run-fake {:on-progress (fn [_] (swap! seen inc) (throw (ex-info "boom" {})))}
                        "--blocks" "2" "--sleep-ms" "10")]
    (is (= 0 (:exit reply)))
    (is (= 2 @seen))))

;; ---------------------------------------------------------------------------
;; Progress parsing (pure)

(deftest parse-progress-line-golden
  (is (= [:frame "20"] (process/parse-progress-line "frame=20")))
  (is (= [:fps "0.00"] (process/parse-progress-line "fps=0.00")))
  (is (= [:stream_0_0_q "3.0"] (process/parse-progress-line "stream_0_0_q=3.0")))
  (is (= [:bitrate "130.7kbits/s"] (process/parse-progress-line "bitrate= 130.7kbits/s")))
  (is (= [:bitrate "N/A"] (process/parse-progress-line "bitrate=N/A")))
  (is (= [:total_size "32620"] (process/parse-progress-line "total_size=32620")))
  (is (= [:out_time_us "1996916"] (process/parse-progress-line "out_time_us=1996916")))
  (is (= [:out_time_ms "1996916"] (process/parse-progress-line "out_time_ms=1996916")))
  (is (= [:out_time "00:00:01.996916"] (process/parse-progress-line "out_time=00:00:01.996916")))
  (is (= [:dup_frames "0"] (process/parse-progress-line "dup_frames=0")))
  (is (= [:speed "1.5x"] (process/parse-progress-line "speed=1.5x")))
  (is (= [:speed "25.2x"] (process/parse-progress-line "speed=25.2x")))
  (is (= [:progress "continue"] (process/parse-progress-line "progress=continue")))
  (is (= [:progress "end"] (process/parse-progress-line "progress=end")))
  (is (= [:progress "end"] (process/parse-progress-line "progress=end\r")) "a CRLF line")
  (is (= [:x ""] (process/parse-progress-line "x=")) "an empty value is still a pair")
  (testing "anything else is nil"
    (is (nil? (process/parse-progress-line "")))
    (is (nil? (process/parse-progress-line "   ")))
    (is (nil? (process/parse-progress-line "frame 20")))
    (is (nil? (process/parse-progress-line "=20")))
    (is (nil? (process/parse-progress-line "[libx264 @ 0x55] frame I:1")))
    (is (nil? (process/parse-progress-line nil)))))

(deftest progress-blocks-splits-at-progress-key-and-drops-a-partial-tail
  (let [lines ["frame=0" "fps=0.00" "bitrate=   1.1kbits/s" "out_time_us=348299" "speed=25.2x" "progress=continue"
               "garbage line without equals"
               "frame=20" "fps=0.00" "bitrate= 130.7kbits/s" "out_time_us=1996916" "speed=32.8x" "progress=end"
               "frame=21" "fps=0.00"]
        blocks (process/progress-blocks lines)]
    (is (= 2 (count blocks)))
    (is (= {:frame "0" :fps "0.00" :bitrate "1.1kbits/s" :out_time_us "348299" :speed "25.2x" :progress "continue"}
           (first blocks)))
    (is (= {:frame "20" :fps "0.00" :bitrate "130.7kbits/s" :out_time_us "1996916" :speed "32.8x" :progress "end"}
           (second blocks))))
  (is (= [] (process/progress-blocks [])))
  (is (= [] (process/progress-blocks ["frame=1" "fps=2"])) "no progress= line, no block")
  (is (= [{:progress "end"}] (process/progress-blocks ["progress=end"]))))

;; ---------------------------------------------------------------------------
;; ffprobe

(defn- with-fake-ffprobe
  "Run `f` with run! starting the fake ffprobe wherever the argv names
   ffprobe (the fixture is a bb script, so a :bins entry cannot name it)."
  [f]
  (let [real process/run!]
    (with-redefs [process/run! (fn [argv opts]
                                 (real (if (= "ffprobe" (first argv))
                                         (into ["bb" fake-ffprobe] (rest argv))
                                         argv)
                                       opts))]
      (f))))

(deftest ffprobe-argv-is-the-documented-command
  (is (= ["ffprobe" "-v" "error" "-print_format" "json" "-show_format" "-show_streams" "-show_error" "in.mp4"]
         (process/ffprobe-argv "in.mp4"))))

(deftest ffprobe-happy-path
  (with-temp-dir
    (fn [dir]
      (let [media (str (fs/path dir "clip.mp4"))
            _ (spit media "not really media, the fake does not look inside")
            reply (with-fake-ffprobe #(process/ffprobe! media {}))]
        (is (true? (:ok reply)))
        (is (= 95400 (:duration-ms reply)) "format.duration \"95.400000\" rounded to integer milliseconds")
        (is (map? (:format reply)))
        (is (= 2 (:nb_streams (:format reply))))
        (is (= "95.400000" (:duration (:format reply))))
        (is (= (str/replace media "\\" "\\\\") (str/replace (:filename (:format reply)) "\\" "\\\\")))
        (is (vector? (:streams reply)))
        (is (= ["video" "audio"] (mapv :codec_type (:streams reply))))
        (is (= [0 1] (mapv :index (:streams reply))))
        (is (= 1280 (:width (first (:streams reply)))))
        (is (= 720 (:height (first (:streams reply)))))
        (is (= "30000/1001" (:r_frame_rate (first (:streams reply)))))
        (is (= #{:ok :format :streams :duration-ms} (set (keys reply))))))))

(deftest ffprobe-error-paths
  (with-temp-dir
    (fn [dir]
      (testing "a file ffprobe cannot open: its error object, exit 1"
        (let [reply (with-fake-ffprobe #(process/ffprobe! (str (fs/path dir "nope.mp4")) {}))]
          (is (= {:ok false :exit 1 :error {:code -2 :string "No such file or directory"}} reply))))
      (testing "a tool that prints no JSON"
        (let [reply (with-fake-ffprobe #(process/ffprobe! (str (fs/path dir "garbage.mp4")) {}))]
          (is (false? (:ok reply)))
          (is (= 0 (:exit reply)))
          (is (string? (:error reply)))
          (is (str/includes? (:error reply) "no JSON"))))
      (testing "ffprobe not installed: the run! exception propagates"
        (let [data (ex-type #(process/ffprobe! "x.mp4" {:bins {"ffprobe" (str (fs/path dir "no-ffprobe"))}}))]
          (is (= :process/binary-missing (:type data)))
          (is (= "ffprobe" (:binary data))))))))

(deftest ffprobe-reply-is-pure-over-golden-replies
  (let [ok (fn [out] {:exit 0 :out out :err "" :duration-ms 1 :timed-out? false :cancelled? false :truncated? false :argv []})]
    (testing "duration from format, rounded"
      (is (= 1234 (:duration-ms (process/ffprobe-reply (ok "{\"format\":{\"duration\":\"1.2344\"},\"streams\":[]}"))))))
    (testing "duration falls back to the longest stream when the format has none"
      (is (= 2500 (:duration-ms (process/ffprobe-reply
                                  (ok "{\"format\":{\"nb_streams\":2},\"streams\":[{\"duration\":\"2.5\"},{\"duration\":\"1.0\"}]}"))))))
    (testing "no duration anywhere is nil, still ok"
      (let [r (process/ffprobe-reply (ok "{\"format\":{},\"streams\":[{\"codec_type\":\"video\"}]}"))]
        (is (true? (:ok r)))
        (is (nil? (:duration-ms r)))))
    (testing "an error object wins even with exit 0"
      (is (= {:ok false :exit 0 :error {:code -1094995529 :string "Invalid data found when processing input"}}
             (process/ffprobe-reply (ok "{\"error\":{\"code\":-1094995529,\"string\":\"Invalid data found when processing input\"}}")))))
    (testing "a non-zero exit without JSON reports stderr"
      (is (= {:ok false :exit 1 :error "boom"}
             (process/ffprobe-reply (assoc (ok "") :exit 1 :err "boom\n")))))
    (testing "a timeout and a cancel are failures"
      (is (true? (:timed-out? (process/ffprobe-reply (assoc (ok "") :exit nil :timed-out? true)))))
      (is (false? (:ok (process/ffprobe-reply (assoc (ok "") :exit nil :cancelled? true))))))))

;; ---------------------------------------------------------------------------
;; The runtime seam

(deftest runner-applies-config-defaults-under-the-callers-opts
  (let [config {:process {:timeout-ms 300 :max-output-bytes 100 :poll-ms 20}
                :bins {"fake-ffmpeg" bb-path}}
        run (process/runner config)
        ctx {:module/id :echo}]
    (testing "the configured :bins resolve the program"
      (let [reply (run ctx ["fake-ffmpeg" fake-ffmpeg "--stdout" "hi"] {})]
        (is (= 0 (:exit reply)))
        (is (= bb-path (first (:argv reply))))
        (is (str/includes? (:out reply) "hi"))))
    (testing "the configured timeout applies when the caller gives none"
      (let [reply (run ctx ["fake-ffmpeg" fake-ffmpeg "--forever"] {})]
        (is (true? (:timed-out? reply)))
        (is (nil? (:exit reply)))))
    (testing "the configured output cap applies, and the caller's opts win over it"
      (is (true? (:truncated? (run ctx ["fake-ffmpeg" fake-ffmpeg "--stdout-bytes" "500"] {}))))
      (is (false? (:truncated? (run ctx ["fake-ffmpeg" fake-ffmpeg "--stdout-bytes" "500"] {:max-output-bytes 10000})))))
    (testing "the caller may add programs, the configuration wins on conflicts"
      (with-temp-dir
        (fn [dir]
          (let [reply (run ctx ["other-bb" fake-ffmpeg "--stdout" "added"] {:bins {"other-bb" bb-path}})]
            (is (= 0 (:exit reply)))
            (is (str/includes? (:out reply) "added")))
          (let [reply (run ctx ["fake-ffmpeg" fake-ffmpeg "--stdout" "still-bb"]
                           {:bins {"fake-ffmpeg" (str (fs/path dir "no-such-program"))}})]
            (is (= 0 (:exit reply)))
            (is (= bb-path (first (:argv reply))))))))
    (testing "nil opts are accepted (clogem.api/run-process! passes {} but a runtime may not)"
      (is (= 0 (:exit (run ctx ["fake-ffmpeg" fake-ffmpeg] nil)))))
    (testing "an unconfigured program is reported"
      (is (= :process/binary-missing (:type (ex-type #(run ctx ["ffmpeg-not-here-7f3a"] {}))))))))

(deftest runner-falls-back-to-the-hub-defaults-for-an-empty-config
  (let [run (process/runner {})
        reply (run {:module/id :echo} ["bb" fake-ffmpeg "--stdout" "ok"] {})]
    (is (= 0 (:exit reply)))
    (is (str/includes? (:out reply) "ok"))))
