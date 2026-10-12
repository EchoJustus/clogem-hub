;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.systemd-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clogem.hub.systemd :as systemd]))

(deftest the-unit-is-rendered-from-the-template-and-never-enabled
  (let [text (systemd/render {:bb "/opt/bb/bb" :hub-dir "/srv/clogem-hub"})]
    (is (str/includes? text "ExecStart=/opt/bb/bb --config /srv/clogem-hub/bb.edn daemon"))
    (is (str/includes? text "WorkingDirectory=/srv/clogem-hub"))
    (is (str/includes? text "KillSignal=SIGTERM"))
    (is (str/includes? text "RestartPreventExitStatus=3") "a second instance must not be restarted in a loop")
    (is (str/includes? text "WantedBy=default.target"))
    (is (not (str/includes? text "{{"))) "every placeholder filled")
  (is (thrown? clojure.lang.ExceptionInfo (systemd/render {:bb "" :hub-dir "/x"})))
  (let [dir (fs/create-temp-dir {:prefix "clogem-systemd"})
        path (str (fs/path dir "systemd" "user" "clogem-hub.service"))]
    (try
      (let [{:keys [next-steps] :as r} (systemd/install! {:path path :bb "/opt/bb/bb" :hub-dir "/srv/clogem-hub"})]
        (is (= path (:path r)))
        (is (fs/exists? path))
        (is (str/includes? (slurp path) "ExecStart=/opt/bb/bb"))
        (is (some #(str/starts-with? % "systemctl --user daemon-reload") next-steps))
        (is (some #(str/includes? % "enable --now clogem-hub") next-steps) "enabling is printed, not run")
        (is (some #(str/includes? % "enable-linger") next-steps))
        (is (some #(str/includes? % "vmIdleTimeout") next-steps) "the WSL idle keys are named for the owner's decision"))
      (finally (fs/delete-tree dir))))
  (is (str/ends-with? (systemd/unit-path) "/systemd/user/clogem-hub.service"))
  (is (string? (systemd/bb-path)))
  (is (str/ends-with? (systemd/hub-dir) "clogem-hub") "bb.edn's directory is the checkout"))
