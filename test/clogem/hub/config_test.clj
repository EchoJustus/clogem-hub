;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.config-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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

(deftest xdg-paths-end-in-clogem
  (doseq [f [config/config-dir config/data-dir config/cache-dir config/runtime-dir]]
    (is (str/ends-with? (f) "/clogem") (str f))))

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
      (finally (fs/delete-tree dir)))))
