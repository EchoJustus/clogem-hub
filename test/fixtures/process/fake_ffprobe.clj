;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

;; Test fixture emulating ffprobe, run as a child process by
;; clogem.hub.media.process-test:
;;   bb test/fixtures/process/fake_ffprobe.clj [ffprobe options] <path>
;; The last argument is the input path, as for ffprobe. When the file
;; exists the golden JSON of a 95.4 s mp4 (one h264 video stream, one aac
;; audio stream) goes to stdout with exit 0, the shapes ffprobe's JSON
;; writer produces (duration, size and bit_rate as strings, width, height
;; and index as integers, r_frame_rate as "num/den"). When it does not
;; exist, ffprobe's behaviour with -show_error: the error line on stderr,
;; the document {"error": {"code": -2, "string": …}} on stdout, exit 1. A
;; path whose name contains "garbage" prints a non-JSON line with exit 0
;; (a broken tool).

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def golden
  "{
    \"streams\": [
        {
            \"index\": 0,
            \"codec_name\": \"h264\",
            \"codec_long_name\": \"H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10\",
            \"profile\": \"High\",
            \"codec_type\": \"video\",
            \"codec_tag_string\": \"avc1\",
            \"codec_tag\": \"0x31637661\",
            \"width\": 1280,
            \"height\": 720,
            \"coded_width\": 1280,
            \"coded_height\": 720,
            \"has_b_frames\": 2,
            \"sample_aspect_ratio\": \"1:1\",
            \"display_aspect_ratio\": \"16:9\",
            \"pix_fmt\": \"yuv420p\",
            \"level\": 31,
            \"r_frame_rate\": \"30000/1001\",
            \"avg_frame_rate\": \"30000/1001\",
            \"time_base\": \"1/30000\",
            \"start_pts\": 0,
            \"start_time\": \"0.000000\",
            \"duration_ts\": 2862000,
            \"duration\": \"95.400000\",
            \"bit_rate\": \"1534621\",
            \"nb_frames\": \"2859\",
            \"disposition\": {
                \"default\": 1,
                \"attached_pic\": 0
            },
            \"tags\": {
                \"language\": \"und\",
                \"handler_name\": \"VideoHandler\",
                \"vendor_id\": \"[0][0][0][0]\"
            }
        },
        {
            \"index\": 1,
            \"codec_name\": \"aac\",
            \"codec_long_name\": \"AAC (Advanced Audio Coding)\",
            \"profile\": \"LC\",
            \"codec_type\": \"audio\",
            \"codec_tag_string\": \"mp4a\",
            \"codec_tag\": \"0x6134706d\",
            \"sample_fmt\": \"fltp\",
            \"sample_rate\": \"48000\",
            \"channels\": 2,
            \"channel_layout\": \"stereo\",
            \"bits_per_sample\": 0,
            \"r_frame_rate\": \"0/0\",
            \"avg_frame_rate\": \"0/0\",
            \"time_base\": \"1/48000\",
            \"start_pts\": 0,
            \"start_time\": \"0.000000\",
            \"duration_ts\": 4579200,
            \"duration\": \"95.400000\",
            \"bit_rate\": \"128000\",
            \"nb_frames\": \"4473\",
            \"disposition\": {
                \"default\": 1,
                \"attached_pic\": 0
            },
            \"tags\": {
                \"language\": \"und\",
                \"handler_name\": \"SoundHandler\",
                \"vendor_id\": \"[0][0][0][0]\"
            }
        }
    ],
    \"format\": {
        \"filename\": \"%s\",
        \"nb_streams\": 2,
        \"nb_programs\": 0,
        \"format_name\": \"mov,mp4,m4a,3gp,3g2,mj2\",
        \"format_long_name\": \"QuickTime / MOV\",
        \"start_time\": \"0.000000\",
        \"duration\": \"95.400000\",
        \"size\": \"19826730\",
        \"bit_rate\": \"1662697\",
        \"probe_score\": 100,
        \"tags\": {
            \"major_brand\": \"isom\",
            \"minor_version\": \"512\",
            \"compatible_brands\": \"isomiso2avc1mp41\",
            \"encoder\": \"Lavf60.16.100\"
        }
    }
}
")

(let [path (last *command-line-args*)
      json-name (str/replace (str path) "\\" "\\\\")]
  (cond
    (nil? path)
    (do (binding [*out* *err*] (println "You have to specify one input file.") (flush))
        (System/exit 1))

    (str/includes? (str (fs/file-name path)) "garbage")
    (do (print "this is not json\n") (flush) (System/exit 0))

    (fs/exists? path)
    (do (print (format golden json-name)) (flush) (System/exit 0))

    :else
    (do (binding [*out* *err*] (println (str path ": No such file or directory")) (flush))
        (print "{\n    \"error\": {\n        \"code\": -2,\n        \"string\": \"No such file or directory\"\n    }\n}\n")
        (flush)
        (System/exit 1))))
