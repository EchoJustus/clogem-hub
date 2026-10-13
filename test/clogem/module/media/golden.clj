;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.module.media.golden
  "Golden ffprobe output shared by the media module's tests (not a test
   namespace itself: `bb test` runs *_test.clj). The document is what
   `ffprobe -v error -print_format json -show_format -show_streams
   -show_error` writes for a 95.4 s mp4 with one h264 video stream and one
   aac audio stream, in the shapes ffprobe's JSON writer uses: duration,
   size and bit_rate as strings, index, width and height as integers.")

(def duration-ms
  "format.duration \"95.400000\" as integer milliseconds."
  95400)

(def ffprobe-json
  "{
    \"streams\": [
        {
            \"index\": 0,
            \"codec_name\": \"h264\",
            \"codec_long_name\": \"H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10\",
            \"profile\": \"High\",
            \"codec_type\": \"video\",
            \"codec_tag_string\": \"avc1\",
            \"width\": 1280,
            \"height\": 720,
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
                \"handler_name\": \"VideoHandler\"
            }
        },
        {
            \"index\": 1,
            \"codec_name\": \"aac\",
            \"codec_long_name\": \"AAC (Advanced Audio Coding)\",
            \"profile\": \"LC\",
            \"codec_type\": \"audio\",
            \"codec_tag_string\": \"mp4a\",
            \"sample_fmt\": \"fltp\",
            \"sample_rate\": \"48000\",
            \"channels\": 2,
            \"channel_layout\": \"stereo\",
            \"r_frame_rate\": \"0/0\",
            \"avg_frame_rate\": \"0/0\",
            \"time_base\": \"1/48000\",
            \"start_pts\": 0,
            \"start_time\": \"0.000000\",
            \"duration_ts\": 4579200,
            \"duration\": \"95.400000\",
            \"bit_rate\": \"128000\",
            \"nb_frames\": \"4471\",
            \"disposition\": {
                \"default\": 1,
                \"attached_pic\": 0
            },
            \"tags\": {
                \"language\": \"und\",
                \"handler_name\": \"SoundHandler\"
            }
        }
    ],
    \"format\": {
        \"filename\": \"sample.mp4\",
        \"nb_streams\": 2,
        \"nb_programs\": 0,
        \"format_name\": \"mov,mp4,m4a,3gp,3g2,mj2\",
        \"format_long_name\": \"QuickTime / MOV\",
        \"start_time\": \"0.000000\",
        \"duration\": \"95.400000\",
        \"size\": \"19821056\",
        \"bit_rate\": \"1662352\",
        \"probe_score\": 100,
        \"tags\": {
            \"major_brand\": \"isom\",
            \"minor_version\": \"512\",
            \"compatible_brands\": \"isomiso2avc1mp41\",
            \"encoder\": \"Lavf60.3.100\"
        }
    }
}
")

(def ffprobe-error-json
  "What -show_error writes on stdout (exit 1) for a file that does not exist."
  "{
    \"error\": {
        \"code\": -2,
        \"string\": \"No such file or directory\"
    }
}
")

(def ffprobe-error-stderr
  "nope.mp4: No such file or directory\n")

(defn process-reply
  "A complete run-process! reply for `argv`, `m` merged over the defaults
   of a successful run with empty output."
  [argv m]
  (merge {:exit 0 :out "" :err "" :duration-ms 1
          :timed-out? false :cancelled? false :truncated? false :argv argv}
         m))
