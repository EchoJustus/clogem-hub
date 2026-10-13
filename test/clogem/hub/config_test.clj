;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.config-test
  (:require [babashka.fs :as fs]
            [clogem.hub.test-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.hub.config :as config]))

(deftest defaults-bind-loopback-on-7788
  (is (= {:host "127.0.0.1" :port 7788 :allowed-origins #{}} (:http config/defaults)))
  (is (= 7788 (get-in (config/load-config) [:http :port]))))

(deftest overrides-deep-merge
  (let [c (config/load-config {:http {:port 0}})]
    (is (= 0 (get-in c [:http :port])))
    (is (= "127.0.0.1" (get-in c [:http :host])))))

(deftest non-loopback-listeners-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loopback"
                        (config/validate {:http {:host "0.0.0.0" :port 7788}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loopback"
                        (config/validate {:http {:host "192.168.1.5" :port 7788}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"port"
                        (config/validate {:http {:host "127.0.0.1" :port 70000}})))
  (is (map? (config/validate {:http {:host "localhost" :port 1}}))))

(deftest directories-live-under-clogem
  ;; config and data end in the clogem folder; cache and runtime are below it on Windows
  (doseq [f [config/config-dir config/data-dir]]
    (is (str/ends-with? (f) (str fs/file-separator "clogem")) (str f)))
  (doseq [f [config/cache-dir config/runtime-dir]]
    (is (str/includes? (f) (str fs/file-separator "clogem")) (str f))))

(deftest paths-per-platform
  (let [sep fs/file-separator
        j (fn [& parts] (str/join sep parts))]
    (testing "Windows defaults under %LOCALAPPDATA%"
      (let [p (config/paths {:env {"LOCALAPPDATA" (j "C:" "Users" "ann" "AppData" "Local")} :windows? true
                             :home (j "C:" "Users" "ann") :tmpdir (j "C:" "Temp") :user "ann"})]
        (is (= (j "C:" "Users" "ann" "AppData" "Local" "clogem") (:config p)))
        (is (= (:config p) (:data p)) "config and data share the folder")
        (is (= (j "C:" "Users" "ann" "AppData" "Local" "clogem" "cache") (:cache p)))
        (is (= (j "C:" "Users" "ann" "AppData" "Local" "clogem" "run") (:runtime p)))
        (is (nil? (:shared p)))))
    (testing "Windows without the variable falls back to the profile"
      (is (= (j "C:" "Users" "ann" "AppData" "Local" "clogem")
             (:config (config/paths {:env {} :windows? true :home (j "C:" "Users" "ann") :tmpdir "t" :user "ann"})))))
    (testing "POSIX defaults are the XDG layout"
      (let [p (config/paths {:env {} :windows? false :home (j "" "home" "ann") :tmpdir (j "" "tmp") :user "ann"})]
        (is (= (j "" "home" "ann" ".config" "clogem") (:config p)))
        (is (= (j "" "home" "ann" ".local" "share" "clogem") (:data p)))
        (is (= (j "" "home" "ann" ".cache" "clogem") (:cache p)))
        (is (= (j "" "tmp" "clogem-ann" "clogem") (:runtime p)))))
    (testing "explicit variables win on both platforms"
      (doseq [w [true false]]
        (let [p (config/paths {:env {"CLOGEM_CONFIG_DIR" (j "x" "cfg") "XDG_DATA_HOME" (j "x" "data") "XDG_CACHE_HOME" (j "x" "cache")
                                     "XDG_RUNTIME_DIR" (j "x" "run") "CLOGEM_SHARED_DIR" (j "x" "shared")
                                     "LOCALAPPDATA" (j "C:" "l")}
                               :windows? w :home "h" :tmpdir "t" :user "u"})]
          (is (= (j "x" "cfg") (:config p)) (str w))
          (is (= (j "x" "data" "clogem") (:data p)))
          (is (= (j "x" "cache" "clogem") (:cache p)))
          (is (= (j "x" "run" "clogem") (:runtime p)))
          (is (= (j "x" "shared") (:shared p))))))
    (is (= "/elsewhere" (config/shared-dir {:shared-dir "/elsewhere"})) "the configuration names the shared directory")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shared-dir"
                          (config/validate (assoc config/defaults :shared-dir " "))))))

(deftest edn-files-are-read-safely
  (let [dir (fs/create-temp-dir {:prefix "clogem-config"})]
    (try
      (is (nil? (config/read-edn-file (fs/path dir "missing.edn"))))
      (spit (str (fs/path dir "vec.edn")) "[1 2]")
      (is (nil? (config/read-edn-file (fs/path dir "vec.edn"))))
      (spit (str (fs/path dir "ok.edn")) "{:http {:port 1234}}")
      (is (= {:http {:port 1234}} (config/read-edn-file (fs/path dir "ok.edn"))))
      (spit (str (fs/path dir "evil.edn")) "#=(+ 1 2)")
      (is (thrown? Exception (config/read-edn-file (fs/path dir "evil.edn"))))
      (finally (support/delete-tree! dir)))))

(deftest local-first-endpoints
  (testing "loopback and LAN hosts are local (PD-9)"
    (doseq [h ["127.0.0.1" "127.0.0.2" "localhost" "LOCALHOST" "::1" "10.0.0.5" "192.168.1.20" "172.16.0.1" "172.31.255.254"
               "169.254.10.10" "fd12:3456::1" "fe80::1"]]
      (is (config/local-host? h) h)))
  (testing "anything else is not"
    (doseq [h ["172.32.0.1" "172.15.0.1" "8.8.8.8" "ollama.example.com" "2001:db8::1" "" nil]]
      (is (not (config/local-host? h)) (pr-str h))))
  (is (= "127.0.0.1" (config/url-host "http://127.0.0.1:11434")))
  (is (= "fd12::1" (config/url-host "http://[fd12::1]:11434/api")))
  (is (= "ollama.example.com" (config/url-host "https://ollama.example.com")))
  (is (nil? (config/url-host "ftp://x")))
  (is (nil? (config/url-host "not a url")))
  (is (nil? (config/url-host nil))))

(deftest s04-keys-are-validated
  (is (= "http://127.0.0.1:11434" (get-in (config/load-config {}) [:llm :ollama :url])))
  (is (= 600000 (get-in (config/load-config {}) [:process :timeout-ms])))
  (is (= "http://192.168.1.20:11434" (get-in (config/load-config {:llm {:ollama {:url "http://192.168.1.20:11434"}}}) [:llm :ollama :url])) "LAN passes")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loopback or LAN"
                        (config/load-config {:llm {:ollama {:url "https://api.example.com"}}})) "a remote endpoint is refused")
  (is (= "https://api.example.com"
         (get-in (config/load-config {:llm {:allow-remote? true :ollama {:url "https://api.example.com"}}}) [:llm :ollama :url]))
      "unless the user overrides")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"http\(s\) URL" (config/load-config {:llm {:ollama {:url "nope"}}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"provider" (config/load-config {:llm {:provider :openai}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timeout-ms" (config/load-config {:llm {:ollama {:timeout-ms 0}}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"connect-timeout-ms" (config/load-config {:llm {:ollama {:connect-timeout-ms "soon"}}})))
  (is (= 10000 (get-in (config/load-config {}) [:llm :ollama :connect-timeout-ms])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bins" (config/load-config {:bins {"ffmpeg" ""}})))
  (is (= {"ffmpeg" "D:\\tools\\ffmpeg.exe"} (:bins (config/load-config {:bins {"ffmpeg" "D:\\tools\\ffmpeg.exe"}}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"process timeout-ms" (config/load-config {:process {:timeout-ms -5}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"modules-file" (config/load-config {:modules-file ""})))
  (is (= "D:\\mods\\modules.edn" (config/modules-file {:modules-file "D:\\mods\\modules.edn"})))
  (is (= (str (fs/path (config/config-dir) "modules.edn")) (config/modules-file {})) "defaults to the config directory"))
