;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

;; Test fixture emulating ffmpeg, run as a child process by
;; clogem.hub.media.process-test:
;;   bb test/fixtures/process/fake_ffmpeg.clj [options]
;; Options, in any order (a flag followed by its value unless noted):
;;   --blocks N         print N `-progress pipe:1` blocks to stdout, the
;;                      keys and formats of ffmpeg's print_report
;;                      (progress=continue, the last one progress=end)
;;   --sleep-ms MS      pause before each block (default 50)
;;   --exit CODE        exit code (default 0)
;;   --stdout TEXT      print TEXT to stdout, before the blocks
;;   --stdout-bytes N   print N bytes of "x" to stdout (truncation tests)
;;   --stderr TEXT      print TEXT to stderr
;;   --echo-stdin       copy stdin to stdout
;;   --print-cwd        print the working directory to stdout
;;   --print-env NAME   print the environment variable NAME to stdout
;;   --forever          never exit (timeout and cancel tests)
;; Everything is flushed as it is written so the parent sees it arrive.

(defn- parse-args [args]
  (loop [[a & more] args opts {}]
    (if (nil? a)
      opts
      (case a
        "--blocks" (recur (rest more) (assoc opts :blocks (Long/parseLong (first more))))
        "--sleep-ms" (recur (rest more) (assoc opts :sleep-ms (Long/parseLong (first more))))
        "--exit" (recur (rest more) (assoc opts :exit (Long/parseLong (first more))))
        "--stdout" (recur (rest more) (assoc opts :stdout (first more)))
        "--stdout-bytes" (recur (rest more) (assoc opts :stdout-bytes (Long/parseLong (first more))))
        "--stderr" (recur (rest more) (assoc opts :stderr (first more)))
        "--print-env" (recur (rest more) (assoc opts :print-env (first more)))
        "--echo-stdin" (recur more (assoc opts :echo-stdin true))
        "--print-cwd" (recur more (assoc opts :print-cwd true))
        "--forever" (recur more (assoc opts :forever true))
        (recur more (update opts :unknown (fnil conj []) a))))))

(defn- emit! [s]
  (print s)
  (flush))

(defn- progress-block
  "One block as ffmpeg's print_report writes it: frame, fps, stream_0_0_q,
   bitrate (%6.1f with the unit), total_size, out_time_us and out_time_ms
   (both microseconds), out_time, dup_frames, drop_frames, speed (%4.3g
   with an x) and progress last."
  [i last?]
  (let [us (* (inc i) 500000)
        secs (quot us 1000000)]
    (str "frame=" (* (inc i) 12) "\n"
         "fps=" (if (zero? i) "0.00" "24.00") "\n"
         "stream_0_0_q=" (if (zero? i) "0.0" "28.0") "\n"
         "bitrate=" (format "%6.1f" (double (+ 1.1 (* i 10)))) "kbits/s\n"
         "total_size=" (+ 48 (* i 4096)) "\n"
         "out_time_us=" us "\n"
         "out_time_ms=" us "\n"
         "out_time=" (format "%02d:%02d:%02d.%06d" (quot secs 3600) (quot (rem secs 3600) 60) (rem secs 60) (rem us 1000000)) "\n"
         "dup_frames=0\n"
         "drop_frames=0\n"
         "speed=" (if (zero? i) "N/A" "1.5x") "\n"
         "progress=" (if last? "end" "continue") "\n")))

(let [{:keys [blocks sleep-ms exit stdout stdout-bytes stderr echo-stdin print-cwd print-env forever unknown]
       :or {sleep-ms 50 exit 0}} (parse-args *command-line-args*)]
  (when (seq unknown)
    (binding [*out* *err*] (println "fake_ffmpeg: ignoring" (pr-str unknown)) (flush)))
  (when stdout (emit! (str stdout "\n")))
  (when stdout-bytes (emit! (apply str (repeat stdout-bytes "x"))))
  (when print-cwd (emit! (str (System/getProperty "user.dir") "\n")))
  (when print-env (emit! (str (System/getenv print-env) "\n")))
  (when echo-stdin (emit! (slurp *in*)))
  (when stderr
    (binding [*out* *err*] (println stderr) (flush)))
  (when blocks
    (dotimes [i blocks]
      (Thread/sleep sleep-ms)
      (emit! (progress-block i (= i (dec blocks))))))
  (when forever
    @(promise))
  (System/exit exit))
