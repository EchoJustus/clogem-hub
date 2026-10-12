;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.platform
  "What differs between Windows and POSIX hosts, in one namespace (ADR-0006):

   - owner-only files: on Windows an NTFS DACL that allows only the user,
     SYSTEM and Administrators; on POSIX mode 0600
   - the volume check behind PD-10: System.IO.DriveInfo (through the in-box
     PowerShell, Babashka cannot call java.nio.file.FileStore methods) on
     Windows, `findmnt` elsewhere
   - the running Babashka binary, for the stdio proxy's autostart
   - the per-user base directory (%LOCALAPPDATA%)

   Every platform-specific call (POSIX permissions, findmnt, powershell) lives
   only here, behind `windows?`; the lint rule :restricted-call refuses them
   in any other namespace. The decisions are pure functions over the tools'
   output (`secure-acl?`, `volume-problem`), so both branches are unit-tested
   on either host; the shell glue is exercised by the Windows CI job."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn windows? [] (fs/windows?))

(defn local-app-data
  "%LOCALAPPDATA%, or <home>\\AppData\\Local when the variable is unset."
  [env home]
  (or (not-empty (get env "LOCALAPPDATA")) (str (fs/path home "AppData" "Local"))))

;; ---------------------------------------------------------------------------
;; PowerShell (Windows only)

(defn ps-literal
  "A single-quoted PowerShell string literal for `s`."
  [s]
  (str "'" (str/replace (str s) "'" "''") "'"))

(defn encoded-command
  "What `powershell -EncodedCommand` takes: Base64 of the UTF-16LE script."
  [script]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes ^String script "UTF-16LE")))

(def ^:private module-path-reset
  "Windows PowerShell 5.1 autoloads Get-Acl from Microsoft.PowerShell.Security
   through PSModulePath; a parent that set that variable for PowerShell 7
   (GitHub's pwsh steps do) makes the 5.1 load fail, so every script first
   points the path back at the 5.1 module directories."
  "$env:PSModulePath=\"$PSHOME\\Modules;$env:ProgramFiles\\WindowsPowerShell\\Modules\"\n")

(defn- powershell!
  "Run `script` in the in-box Windows PowerShell; its stdout, or ex-info."
  [script]
  (let [{:keys [exit out err]} (p/shell {:out :string :err :string :continue true}
                                        "powershell" "-NoProfile" "-NonInteractive" "-ExecutionPolicy" "Bypass"
                                        "-EncodedCommand" (encoded-command (str module-path-reset script)))]
    (if (zero? exit)
      (str out)
      (throw (ex-info (str "powershell failed (" exit "): " (str/trim (str err)))
                      {:type :powershell-failed :exit exit})))))

;; ---------------------------------------------------------------------------
;; Owner-only files

(def well-known-sids
  {:system "S-1-5-18" :administrators "S-1-5-32-544"
   :everyone "S-1-1-0" :users "S-1-5-32-545" :authenticated-users "S-1-5-11"})

(defn acl-script
  "PowerShell that prints the caller's SID, the owner's SID and every access
   control entry of `path` as SIDs (locale-independent)."
  [path]
  (str "$ErrorActionPreference='Stop'\n"
       "$p=" (ps-literal path) "\n"
       "$acl=Get-Acl -LiteralPath $p\n"
       "'ME;'+[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value\n"
       "'OWNER;'+$acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value\n"
       "foreach($r in $acl.Access){$s=$r.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value;"
       "'ACE;'+$r.AccessControlType+';'+$s+';'+$r.FileSystemRights}\n"))

(defn parse-acl-report
  "{:me sid :owner sid :aces [{:type \"Allow\"|\"Deny\" :sid s :rights r} ...]}
   from the lines `acl-script` prints."
  [text]
  (reduce (fn [m line]
            (let [[kind & more] (str/split (str/trim line) #";")]
              (case kind
                "ME" (assoc m :me (first more))
                "OWNER" (assoc m :owner (first more))
                "ACE" (update m :aces conj {:type (first more) :sid (second more) :rights (nth more 2 nil)})
                m)))
          {:aces []}
          (remove str/blank? (str/split-lines (str text)))))

(defn secure-acl?
  "True when every Allow entry names the caller, SYSTEM or Administrators:
   the Windows reading of mode 0600. Deny entries only restrict and are
   ignored; a report without entries or without the caller's SID is not
   secure (nothing could prove it)."
  [{:keys [me aces]}]
  (boolean
   (and me (seq aces)
        (every? (fn [{:keys [type sid]}]
                  (or (not= "Allow" type)
                      (contains? #{me (:system well-known-sids) (:administrators well-known-sids)} sid)))
                aces))))

(defn set-acl-script
  "PowerShell that replaces `path`'s DACL (inheritance off) with full control
   for the caller, SYSTEM and Administrators."
  [path]
  (str "$ErrorActionPreference='Stop'\n"
       "$p=" (ps-literal path) "\n"
       "$me=[System.Security.Principal.WindowsIdentity]::GetCurrent().User\n"
       "$acl=Get-Acl -LiteralPath $p\n"
       "$acl.SetAccessRuleProtection($true,$false)\n"
       "foreach($r in @($acl.Access)){[void]$acl.RemoveAccessRule($r)}\n"
       "foreach($s in @($me,[System.Security.Principal.SecurityIdentifier]'S-1-5-18',[System.Security.Principal.SecurityIdentifier]'S-1-5-32-544')){"
       "$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($s,'FullControl','Allow')))}\n"
       "Set-Acl -LiteralPath $p -AclObject $acl\n"))

(def ^:private owner-only #{"OWNER_READ" "OWNER_WRITE"})

(defn secure-file?
  "True when only this user can read or write `path`: mode 0600 or 0400 on
   POSIX, an owner-only DACL on Windows. False when it cannot be proven."
  [path]
  (try
    (if (windows?)
      (secure-acl? (parse-acl-report (powershell! (acl-script (str (fs/absolutize path))))))
      (let [perms (set (map str (fs/posix-file-permissions path)))]
        (and (contains? perms "OWNER_READ") (empty? (remove owner-only perms)))))
    (catch Exception _ false)))

(defn secure-file!
  "Make `path` readable and writable by this user only (see `secure-file?`).
   Returns path."
  [path]
  (if (windows?)
    (powershell! (set-acl-script (str (fs/absolutize path))))
    (fs/set-posix-file-permissions path "rw-------"))
  path)

;; ---------------------------------------------------------------------------
;; Volumes (PD-10)

(def unsafe-filesystems
  "POSIX filesystem types the database must not live on: Windows mounts
   under WSL and network shares."
  #{"9p" "v9fs" "drvfs" "cifs" "smb3" "nfs" "nfs4" "fuse.sshfs"})

(def windows-filesystems #{"NTFS" "REFS"})

(defn unc-path? [path]
  (boolean (re-matches #"(?i)^(\\\\|//)[^\\/]+[\\/].*" (str path))))

(defn- nearest-existing [path]
  (loop [p (fs/absolutize path)]
    (if (or (nil? p) (fs/exists? p)) p (recur (fs/parent p)))))

(defn volume-script
  "PowerShell that prints `<DriveType>;<DriveFormat>` of the drive holding
   `root` (a path such as C:\\)."
  [root]
  (str "$ErrorActionPreference='Stop'\n"
       "$d=New-Object System.IO.DriveInfo(" (ps-literal root) ")\n"
       "\"$($d.DriveType);$($d.DriveFormat)\"\n"))

(defn parse-volume-report [text]
  (let [[drive-type format] (str/split (str/trim (str text)) #";")]
    {:drive-type (not-empty drive-type) :fstype (not-empty format)}))

(defn volume-info
  "{:os :windows|:posix :path abs :fstype type [:drive-type t]} for the
   volume holding `path` (its nearest existing ancestor). Unknown values are
   nil, never an exception."
  [path]
  (let [abs (str (fs/absolutize path))]
    (if (windows?)
      (merge {:os :windows :path abs}
             (when-not (unc-path? abs)
               (try (parse-volume-report (powershell! (volume-script (str (.getRoot (fs/path abs))))))
                    (catch Exception _ nil))))
      {:os :posix :path abs
       :fstype (try (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                                      "findmnt" "-n" "-o" "FSTYPE" "-T" (str (nearest-existing abs)))]
                      (when (zero? exit) (not-empty (str/trim out))))
                    (catch Exception _ nil))})))

(defn volume-problem
  "Why the volume in a `volume-info` map may not hold the database, or nil.
   Windows: UNC paths, anything but a fixed drive, anything but NTFS or ReFS.
   POSIX: the unsafe filesystem types. Unknown values pass (a warning is the
   caller's job)."
  [{:keys [os path drive-type fstype]}]
  (if (= :windows os)
    (cond
      (unc-path? path) "a UNC path (network share)"
      (and drive-type (not= "Fixed" drive-type)) (str "a " drive-type " drive")
      (and fstype (not (contains? windows-filesystems (str/upper-case fstype)))) (str "a " fstype " volume (NTFS or ReFS needed)")
      :else nil)
    (when (contains? unsafe-filesystems fstype)
      (str "a " fstype " filesystem"))))

(defn cloud-placeholder?
  "True when `path` lies under a cloud-synced folder named by the OneDrive
   environment variables (a sync client can lock or corrupt a live database)."
  ([path] (cloud-placeholder? path (System/getenv)))
  ([path env]
   (let [abs (str/lower-case (str (fs/absolutize path)))]
     (boolean (some (fn [k] (when-let [root (not-empty (get env k))]
                              (str/starts-with? abs (str/lower-case (str (fs/absolutize root))))))
                    ["OneDrive" "OneDriveConsumer" "OneDriveCommercial"])))))

;; ---------------------------------------------------------------------------
;; This process

(defn current-binary
  "The executable running this process (the packaged bb on Windows), or
   \"bb\" from PATH when the OS does not tell."
  []
  (or (try (.orElse (.command (.info (java.lang.ProcessHandle/current))) nil)
           (catch Exception _ nil))
      "bb"))
