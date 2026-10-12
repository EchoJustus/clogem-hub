;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.auth
  "Per-client bearer tokens mapped to profiles (ADR-0005).

   `clients.edn` (readable by this user only: mode 0600 on POSIX, an
   owner-only DACL on Windows, see clogem.hub.platform; under the config
   directory) is
   {:clients {\"<client-id>\" {:profile :gui|:llm-small|:llm-large|:admin
                              :token \"<secret>\"}}}.
   The daemon creates it on first start with one client per default profile
   and random tokens; nothing else ever writes it. A file that another user
   could read is refused, not repaired. Every HTTP request must carry
   `Authorization: Bearer <token>`; the matching client's profile decides
   which tools and resources the request sees and may call. Tokens are
   compared in constant time and never logged (clogem.hub.log redacts the
   key names)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clogem.hub.log :as log]
            [clogem.hub.platform :as platform]
            [clogem.sdk.manifest :as manifest]))

(def profiles
  "The client profiles the SDK defines for tools, resources and prompts."
  manifest/profiles)

(def default-clients
  "Clients created on first start: id → profile. `stdio` is what the stdio
   proxy speaks as; `llm` is for an LLM host over HTTP; `gui` for the studio."
  {"admin" :admin
   "gui" :gui
   "llm" :llm-large
   "llm-small" :llm-small
   "stdio" :llm-large})

(def ^:private secure-random (java.security.SecureRandom.))

(defn new-token
  "A fresh 256-bit token, base64url without padding (43 characters)."
  []
  (let [buf (byte-array 32)]
    (.nextBytes secure-random buf)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) buf)))

(defn generate
  "A clients map for `ids->profiles` (default `default-clients`)."
  ([] (generate default-clients))
  ([ids->profiles]
   {:clients (into (sorted-map)
                   (for [[id profile] ids->profiles]
                     [id {:profile profile :token (new-token)}]))}))

(defn secure-permissions?
  "True when only this user can read or write `path`: mode 0600 or 0400 on
   POSIX, an owner-only DACL on Windows (clogem.hub.platform/secure-file?)."
  [path]
  (platform/secure-file? path))

(defn problems
  "Why `m` is not a valid clients map, or an empty vector."
  [m]
  (cond
    (not (map? m)) ["clients.edn must be a map"]
    (not (map? (:clients m))) [":clients must be a map of client id to {:profile :token}"]
    :else
    (vec (for [[id {:keys [profile token] :as c}] (:clients m)
               problem (cond-> []
                         (not (and (string? id) (re-matches #"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}" id)))
                         (conj (str "client id " (pr-str id) " must match [A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
                         (not (map? c)) (conj (str "client " (pr-str id) " must be a map"))
                         (not (contains? profiles profile))
                         (conj (str "client " (pr-str id) " profile must be one of " (pr-str (sort profiles))))
                         (not (and (string? token) (>= (count token) 16)))
                         (conj (str "client " (pr-str id) " token must be a string of at least 16 characters")))]
           problem))))

(defn write-clients!
  "Write `clients` to `path` readable by this user only: a temp file in the
   same directory, made owner-only before any secret is written, then an
   atomic move. The config directory is created when missing."
  [path clients]
  (let [path (str path)
        dir (fs/parent (fs/absolutize path))
        _ (fs/create-dirs dir)
        tmp (fs/create-temp-file {:dir (str dir) :prefix ".clients" :suffix ".edn"})]
    (try
      (platform/secure-file! tmp)
      (spit (str tmp) (str ";; clogem-hub client tokens. Owner-only file: never share, never commit.\n"
                           ";; {:clients {\"<id>\" {:profile :gui|:llm-small|:llm-large|:admin :token \"<secret>\"}}}\n"
                           (pr-str clients) "\n"))
      (fs/move tmp path {:replace-existing true :atomic-move true})
      (platform/secure-file! path)
      path
      (finally (when (fs/exists? tmp) (fs/delete tmp))))))

(defn read-clients
  "The validated clients map at `path`. Throws ex-info {:type :insecure-clients-file}
   when other users can read it and {:type :invalid-clients-file} when its
   content is wrong."
  [path]
  (when-not (secure-permissions? path)
    (throw (ex-info (str "clients file " path " must be readable by its owner only (mode 0600, or an owner-only ACL on Windows)")
                    {:type :insecure-clients-file :path (str path)})))
  (let [m (try (edn/read-string {:eof nil} (slurp (str path)))
               (catch Exception e
                 (throw (ex-info (str "clients file " path " is not valid EDN") {:type :invalid-clients-file :path (str path)} e))))
        ps (problems m)]
    (when (seq ps)
      (throw (ex-info (str "clients file " path " is invalid: " (str/join "; " ps))
                      {:type :invalid-clients-file :path (str path) :problems ps})))
    m))

(defn load-clients!
  "The clients at `path`, creating the file with `default-clients` when it
   does not exist yet. Returns the clients map."
  [path]
  (if (fs/exists? path)
    (read-clients path)
    (let [m (generate)]
      (write-clients! path m)
      (log/info {:msg "clients.edn created" :path (str path) :clients (vec (keys (:clients m)))})
      m)))

(defn client-token
  "The token of client `id`, or nil (for `bb token` and the stdio proxy)."
  [clients id]
  (get-in clients [:clients id :token]))

(defn export-tokens!
  "Write one owner-only file `<dir>/<id>.token` per client (the shared
   directory other packages of the same publisher read). Files of clients
   that no longer exist are removed. Returns the directory."
  [dir clients]
  (let [dir (str dir)]
    (fs/create-dirs dir)
    (doseq [f (fs/glob dir "*.token")]
      (when-not (contains? (:clients clients) (str/replace (fs/file-name f) #"\.token$" ""))
        (fs/delete f)))
    (doseq [[id {:keys [token]}] (:clients clients)]
      (let [f (str (fs/path dir (str id ".token")))]
        (when-not (fs/exists? f) (spit f ""))
        (platform/secure-file! f)
        (spit f token)))
    dir))

(defn- constant-time-equal? [^String a ^String b]
  (java.security.MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))

(defn bearer-token
  "The token in an `Authorization: Bearer <token>` header value, or nil."
  [authorization]
  (when (string? authorization)
    (let [[_ token] (re-matches #"(?i)\s*bearer\s+(\S+)\s*" authorization)]
      token)))

(defn authenticate
  "{:client id :profile p} for the client whose token the Authorization
   header carries, or nil. Every configured token is compared, in constant
   time, so timing does not reveal which id a guess hit."
  [clients authorization]
  (when-let [token (bearer-token authorization)]
    (reduce (fn [found [id {:keys [profile] :as c}]]
              (let [t (:token c)]
                (if (and (string? t) (constant-time-equal? t token))
                  (or found {:client id :profile profile})
                  found)))
            nil
            (:clients clients))))
