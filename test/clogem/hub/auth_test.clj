;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.auth-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [clogem.hub.auth :as auth]
            [clogem.hub.log :as log]))

(deftest generated-clients-cover-every-profile
  (let [m (auth/generate)]
    (is (= [] (auth/problems m)))
    (is (= ["admin" "gui" "llm" "llm-small" "stdio"] (keys (:clients m))))
    (is (= #{:admin :gui :llm-large :llm-small} (set (map :profile (vals (:clients m))))))
    (is (every? #(re-matches #"[A-Za-z0-9_-]{43}" (:token %)) (vals (:clients m))) "256-bit base64url tokens")
    (is (= 5 (count (set (map :token (vals (:clients m)))))) "all distinct")
    (is (not= (auth/new-token) (auth/new-token)))))

(deftest problems-name-every-defect
  (is (= ["clients.edn must be a map"] (auth/problems [])))
  (is (= [":clients must be a map of client id to {:profile :token}"] (auth/problems {:clients []})))
  (is (some #(re-find #"profile" %) (auth/problems {:clients {"x" {:profile :root :token "0123456789abcdef"}}})))
  (is (some #(re-find #"token" %) (auth/problems {:clients {"x" {:profile :gui :token "short"}}})))
  (is (some #(re-find #"client id" %) (auth/problems {:clients {"bad id" {:profile :gui :token "0123456789abcdef"}}})))
  (is (some #(re-find #"client id" %) (auth/problems {:clients {:kw {:profile :gui :token "0123456789abcdef"}}}))))

(deftest the-file-is-created-0600-and-refused-when-open
  (log/set-level! :error)
  (let [dir (fs/create-temp-dir {:prefix "clogem-auth"})
        path (str (fs/path dir "sub" "clients.edn"))]
    (try
      (testing "first load creates the file with mode 0600"
        (let [m (auth/load-clients! path)]
          (is (fs/exists? path))
          (is (auth/secure-permissions? path))
          (is (= m (auth/load-clients! path)) "a second load reads the same tokens")
          (is (= m (auth/read-clients path)))))
      (testing "a readable-by-others file is refused, not repaired"
        (fs/set-posix-file-permissions path "rw-r-----")
        (is (= :insecure-clients-file (:type (try (auth/read-clients path) (catch clojure.lang.ExceptionInfo e (ex-data e))))))
        (is (= "rw-r-----" (fs/posix->str (fs/posix-file-permissions path))) "permissions untouched")
        (fs/set-posix-file-permissions path "rw-------"))
      (testing "invalid content is refused with the problems"
        (auth/write-clients! path {:clients {"x" {:profile :gui :token "short"}}})
        (let [e (try (auth/read-clients path) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= :invalid-clients-file (:type (ex-data e))))
          (is (seq (:problems (ex-data e)))))
        (spit path "{:clients")
        (is (= :invalid-clients-file (:type (try (auth/read-clients path) (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
      (testing "write-clients! replaces atomically and keeps 0600"
        (let [m (auth/generate {"only" :admin})]
          (auth/write-clients! path m)
          (is (= m (auth/read-clients path)))
          (is (auth/secure-permissions? path))
          (is (empty? (filter #(re-find #"^\.clients" (fs/file-name %)) (fs/list-dir (fs/parent path)))) "no temp file left")))
      (finally (log/set-level! :info) (fs/delete-tree dir)))))

(deftest authentication-is-by-token-only
  (let [m (auth/generate)
        gui (auth/client-token m "gui")]
    (is (= {:client "gui" :profile :gui} (auth/authenticate m (str "Bearer " gui))))
    (is (= {:client "gui" :profile :gui} (auth/authenticate m (str "  bearer   " gui "  "))) "scheme is case-insensitive, padding tolerated")
    (is (nil? (auth/authenticate m (str "Basic " gui))))
    (is (nil? (auth/authenticate m gui)) "a bare token is not a bearer header")
    (is (nil? (auth/authenticate m (str "Bearer " gui "x"))))
    (is (nil? (auth/authenticate m (str "Bearer " (subs gui 0 42)))))
    (is (nil? (auth/authenticate m nil)))
    (is (nil? (auth/authenticate m "")))
    (is (nil? (auth/authenticate {:clients {}} (str "Bearer " gui))))
    (is (= gui (auth/bearer-token (str "Bearer " gui))))
    (is (nil? (auth/bearer-token "Bearer a b")))
    (is (nil? (auth/client-token m "nobody")))))
