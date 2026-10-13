;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.probe-test
  "ffprobe parsing over golden documents, and probe! through the SDK test
   kit's fake run-process! (no ffprobe is needed)."
  (:require [clojure.test :refer [deftest is testing]]
            [clogem.module.media.golden :as golden]
            [clogem.module.media.probe :as probe]
            [clogem.sdk.testkit :as testkit]))

(defn- ex-data-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest argv-is-the-documented-command
  (is (= ["ffprobe" "-v" "error" "-print_format" "json" "-show_format" "-show_streams" "-show_error" "in.mp4"]
         (probe/argv "in.mp4"))))

(deftest seconds-are-rounded-to-integer-milliseconds
  (is (= 95400 (probe/seconds->ms "95.400000")))
  (is (= 1234 (probe/seconds->ms "1.2344")))
  (is (= 1235 (probe/seconds->ms "1.2345")))
  (is (= 2500 (probe/seconds->ms 2.5)))
  (is (= 0 (probe/seconds->ms "0.000000")))
  (is (nil? (probe/seconds->ms nil)))
  (is (nil? (probe/seconds->ms "N/A")))
  (is (nil? (probe/seconds->ms "Infinity"))))

(deftest parse-golden-document
  (let [{:keys [format streams duration-ms]} (probe/parse golden/ffprobe-json)]
    (is (= golden/duration-ms duration-ms) "format.duration \"95.400000\" → 95400 ms")
    (is (= "mov,mp4,m4a,3gp,3g2,mj2" (:format_name format)))
    (is (= "95.400000" (:duration format)) "the format map is kept as ffprobe wrote it")
    (is (vector? streams))
    (is (= ["video" "audio"] (map :codec_type streams)))
    (is (= ["h264" "aac"] (map :codec_name streams)))
    (is (= [1280 720] ((juxt :width :height) (first streams))))
    (is (= #{:format :streams :duration-ms} (set (keys (probe/parse golden/ffprobe-json)))))))

(deftest parse-duration-fallbacks
  (testing "the longest stream duration when the format has none"
    (is (= 2500 (:duration-ms (probe/parse "{\"format\":{\"nb_streams\":2},\"streams\":[{\"duration\":\"2.5\"},{\"duration\":\"1.0\"}]}")))))
  (testing "nil when nothing carries a duration"
    (let [r (probe/parse "{\"format\":{},\"streams\":[{\"codec_type\":\"video\"}]}")]
      (is (nil? (:duration-ms r)))
      (is (= [{:codec_type "video"}] (:streams r)))))
  (testing "a document without streams or format"
    (is (= {:format {} :streams [] :duration-ms nil} (probe/parse "{}")))))

(deftest parse-rejects-errors-and-garbage
  (testing "ffprobe's error object"
    (let [data (ex-data-of #(probe/parse golden/ffprobe-error-json))]
      (is (= :media/probe-failed (:type data)))
      (is (= :ffprobe-error (:reason data)))
      (is (= {:code -2 :string "No such file or directory"} (:error data)))))
  (testing "not JSON"
    (doseq [out ["" nil "not json at all" "[1,2,3]"]]
      (let [data (ex-data-of #(probe/parse out))]
        (is (= :media/probe-failed (:type data)) (pr-str out))
        (is (= :invalid-json (:reason data)) (pr-str out))))))

(defn- runtime-with [process]
  (testkit/runtime {:process process}))

(deftest probe!-runs-ffprobe-through-the-api-and-parses
  (let [rt (runtime-with (fn [_argv _opts] {:out golden/ffprobe-json}))
        ctx (testkit/ctx rt :media)
        r (probe/probe! ctx "in.mp4")]
    (is (= golden/duration-ms (:duration-ms r)))
    (is (= 2 (count (:streams r))))
    (is (= [{:argv (probe/argv "in.mp4") :opts {:timeout-ms probe/default-timeout-ms}}]
           (testkit/processes rt))
        "one run, the documented argv, the default timeout")))

(deftest probe!-honours-a-configured-timeout
  (let [rt (testkit/runtime {:process (fn [_ _] {:out golden/ffprobe-json}) :config {:probe-timeout-ms 1234}})]
    (probe/probe! (testkit/ctx rt :media) "in.mp4")
    (is (= {:timeout-ms 1234} (:opts (first (testkit/processes rt)))))))

(deftest probe!-failures
  (testing "the error object wins over the exit code and stderr"
    (let [rt (runtime-with (fn [_ _] {:exit 1 :out golden/ffprobe-error-json :err golden/ffprobe-error-stderr}))
          data (ex-data-of #(probe/probe! (testkit/ctx rt :media) "nope.mp4"))]
      (is (= :media/probe-failed (:type data)))
      (is (= {:code -2 :string "No such file or directory"} (:error data)))
      (is (= 1 (:exit data)))
      (is (= "nope.mp4" (:path data)))
      (is (= "nope.mp4: No such file or directory" (:stderr data)))))
  (testing "a non-zero exit without a document reports stderr"
    (let [rt (runtime-with (fn [_ _] {:exit 1 :out "" :err "boom\n"}))
          e (try (probe/probe! (testkit/ctx rt :media) "x.mp4") nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :media/probe-failed (:type (ex-data e))))
      (is (= :invalid-json (:reason (ex-data e))))
      (is (= "boom" (:stderr (ex-data e))))
      (is (= "ffprobe failed: boom" (ex-message e)))))
  (testing "exit 0 with garbage on stdout"
    (let [rt (runtime-with (fn [_ _] {:exit 0 :out "ffprobe version 7.1\n"}))
          data (ex-data-of #(probe/probe! (testkit/ctx rt :media) "x.mp4"))]
      (is (= :media/probe-failed (:type data)))
      (is (= :invalid-json (:reason data)))
      (is (= 0 (:exit data)))))
  (testing "a valid document with a non-zero exit is still a failure"
    (let [rt (runtime-with (fn [_ _] {:exit 1 :out golden/ffprobe-json :err "warning\n"}))
          data (ex-data-of #(probe/probe! (testkit/ctx rt :media) "x.mp4"))]
      (is (= :media/probe-failed (:type data)))
      (is (= 1 (:exit data)))))
  (testing "timeout and cancellation"
    (let [rt (runtime-with (fn [_ _] {:exit nil :timed-out? true}))
          data (ex-data-of #(probe/probe! (testkit/ctx rt :media) "x.mp4"))]
      (is (= :media/probe-failed (:type data)))
      (is (true? (:timed-out? data))))
    (let [rt (runtime-with (fn [_ _] {:exit nil :cancelled? true}))
          data (ex-data-of #(probe/probe! (testkit/ctx rt :media) "x.mp4"))]
      (is (= :media/probe-failed (:type data)))
      (is (true? (:cancelled? data)))))
  (testing "ffprobe not installed: the api's own error propagates"
    (let [rt (runtime-with (fn [_ _] (throw (ex-info "not installed" {:type :process/binary-missing :binary "ffprobe"}))))
          data (ex-data-of #(probe/probe! (testkit/ctx rt :media) "x.mp4"))]
      (is (= :process/binary-missing (:type data)))
      (is (= "ffprobe" (:binary data))))))
