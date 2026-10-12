;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.platform-test
  (:require [babashka.fs :as fs]
            [clogem.hub.test-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clogem.hub.platform :as platform]))

(deftest powershell-encoding
  (is (= "'D:\\Media\\O''Brien\\x'" (platform/ps-literal "D:\\Media\\O'Brien\\x")))
  (let [script "Write-Output 'héllo'"
        encoded (platform/encoded-command script)]
    (is (re-matches #"[A-Za-z0-9+/=]+" encoded))
    (is (= script (String. (.decode (java.util.Base64/getDecoder) encoded) "UTF-16LE")) "round-trips as UTF-16LE")))

(deftest acl-reports-decide-owner-only
  (let [me "S-1-5-21-1-2-3-1001"
        report (str "ME;" me "\r\nOWNER;" me "\r\n"
                    "ACE;Allow;S-1-5-18;FullControl\r\n"
                    "ACE;Allow;S-1-5-32-544;FullControl\r\n"
                    "ACE;Allow;" me ";FullControl\r\n")
        parsed (platform/parse-acl-report report)]
    (is (= me (:me parsed)))
    (is (= me (:owner parsed)))
    (is (= 3 (count (:aces parsed))))
    (is (= {:type "Allow" :sid "S-1-5-18" :rights "FullControl"} (first (:aces parsed))))
    (is (platform/secure-acl? parsed) "user, SYSTEM and Administrators only")
    (is (platform/secure-acl? (platform/parse-acl-report (str "ME;" me "\nACE;Allow;" me ";FullControl\nACE;Deny;S-1-1-0;Read")))
        "a Deny for Everyone only restricts")
    (is (not (platform/secure-acl? (platform/parse-acl-report (str report "ACE;Allow;S-1-1-0;ReadAndExecute, Synchronize"))))
        "Everyone may read: not secure")
    (is (not (platform/secure-acl? (platform/parse-acl-report (str report "ACE;Allow;S-1-5-32-545;ReadAndExecute"))))
        "Users may read: not secure")
    (is (not (platform/secure-acl? (platform/parse-acl-report (str report "ACE;Allow;S-1-5-11;Read")))) "Authenticated Users: not secure")
    (is (not (platform/secure-acl? (platform/parse-acl-report "ME;S-1-5-21-9\n"))) "no entries: nothing proven")
    (is (not (platform/secure-acl? (platform/parse-acl-report ""))) "no report: not secure")
    (is (not (platform/secure-acl? (platform/parse-acl-report (str "ACE;Allow;" me ";FullControl")))) "without the caller's SID nothing can be proven"))
  (testing "the scripts quote the path and speak in SIDs"
    (let [s (platform/acl-script "D:\\hub\\Ann's\\clients.edn")]
      (is (str/includes? s "'D:\\hub\\Ann''s\\clients.edn'"))
      (is (str/includes? s "SecurityIdentifier")))
    (let [s (platform/set-acl-script "D:\\x\\clients.edn")]
      (is (str/includes? s "SetAccessRuleProtection($true,$false)"))
      (is (str/includes? s "S-1-5-18"))
      (is (str/includes? s "S-1-5-32-544"))
      (is (str/includes? s "Set-Acl")))))

(deftest volume-decisions
  (testing "Windows"
    (is (nil? (platform/volume-problem {:os :windows :path "D:\\hub\\clogem.db" :drive-type "Fixed" :fstype "NTFS"})))
    (is (nil? (platform/volume-problem {:os :windows :path "D:\\data\\clogem.db" :drive-type "Fixed" :fstype "ReFS"})))
    (is (nil? (platform/volume-problem {:os :windows :path "C:\\x" :drive-type nil :fstype nil})) "unknown passes (warned by the caller)")
    (is (re-find #"UNC" (platform/volume-problem {:os :windows :path "\\\\server\\share\\clogem.db" :drive-type "Fixed" :fstype "NTFS"})))
    (is (re-find #"UNC" (platform/volume-problem {:os :windows :path "//server/share/clogem.db"})))
    (is (re-find #"Network" (platform/volume-problem {:os :windows :path "Z:\\clogem.db" :drive-type "Network" :fstype "NTFS"})))
    (is (re-find #"Removable" (platform/volume-problem {:os :windows :path "E:\\clogem.db" :drive-type "Removable" :fstype "NTFS"})))
    (is (re-find #"FAT32" (platform/volume-problem {:os :windows :path "E:\\clogem.db" :drive-type "Fixed" :fstype "FAT32"})))
    (is (re-find #"exFAT" (platform/volume-problem {:os :windows :path "E:\\clogem.db" :drive-type "Fixed" :fstype "exFAT"})))
    (is (= {:drive-type "Fixed" :fstype "NTFS"} (platform/parse-volume-report "Fixed;NTFS\r\n")))
    (is (= {:drive-type nil :fstype nil} (platform/parse-volume-report "")))
    (is (str/includes? (platform/volume-script "C:\\") "System.IO.DriveInfo('C:\\')")))
  (testing "POSIX"
    (is (nil? (platform/volume-problem {:os :posix :path "/srv/x/clogem.db" :fstype "ext4"})))
    (is (nil? (platform/volume-problem {:os :posix :path "/tmp/x" :fstype nil})))
    (doseq [t ["9p" "v9fs" "drvfs" "cifs" "nfs"]]
      (is (re-find (re-pattern t) (platform/volume-problem {:os :posix :path "/mnt/d/x" :fstype t})) t))))

(deftest unc-and-cloud-paths
  (is (platform/unc-path? "\\\\nas\\media\\x.db"))
  (is (platform/unc-path? "//nas/media/x.db"))
  (is (not (platform/unc-path? "D:\\profiles\\x")))
  (is (not (platform/unc-path? "/srv/x")))
  (let [env {"OneDrive" "D:\\profiles\\x\\OneDrive"}]
    (is (platform/cloud-placeholder? "D:\\profiles\\x\\OneDrive\\clogem\\clogem.db" env))
    (is (platform/cloud-placeholder? "d:\\profiles\\x\\onedrive\\x.db" env) "case-insensitive")
    (is (not (platform/cloud-placeholder? "D:\\profiles\\x\\AppData\\Local\\clogem\\clogem.db" env)))
    (is (not (platform/cloud-placeholder? "D:\\profiles\\x\\OneDrive\\x.db" {})) "no OneDrive variable, no warning")))

(deftest local-app-data-and-binary
  (is (= "D:\\profiles\\x\\AppData\\Local" (platform/local-app-data {"LOCALAPPDATA" "D:\\profiles\\x\\AppData\\Local"} "D:\\profiles\\x")))
  (is (= (str (fs/path "/srv/x" "AppData" "Local")) (platform/local-app-data {} "/srv/x")))
  (is (string? (platform/current-binary)))
  (is (boolean? (platform/windows?))))

(deftest owner-only-files-on-this-host
  ;; the real thing on whichever OS runs the tests: CI covers both
  (let [dir (fs/create-temp-dir {:prefix "clogem-platform"})
        f (str (fs/path dir "secret.edn"))]
    (try
      (spit f "{}")
      (platform/secure-file! f)
      (is (platform/secure-file? f) "owner-only after secure-file!")
      (is (= "{}" (slurp f)) "still readable by the owner")
      (is (not (platform/secure-file? (str (fs/path dir "missing.edn")))) "a missing file proves nothing")
      (finally (support/delete-tree! dir)))))
