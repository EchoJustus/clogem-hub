;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.systemd
  "The systemd user unit (`bb systemd:install`): renders the template in
   resources/clogem/hub/systemd with the bb binary and the hub directory,
   writes it under ~/.config/systemd/user/ and returns the commands the
   owner runs next. Enabling a unit, linger and WSL idle settings are the
   owner's decisions, so nothing here calls systemctl (ADR-0005)."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def unit-name "clogem-hub.service")
(def unit-resource "clogem/hub/systemd/clogem-hub.service")

(defn hub-dir
  "The hub checkout: the directory of the bb.edn Babashka loaded."
  []
  (some-> (System/getProperty "babashka.config") fs/absolutize fs/parent str))

(defn bb-path
  "The bb binary the unit runs: $CLOGEM_BB, else the one on PATH."
  []
  (or (not-empty (System/getenv "CLOGEM_BB"))
      (some-> (fs/which "bb") str)
      "bb"))

(defn unit-path
  "~/.config/systemd/user/clogem-hub.service (XDG_CONFIG_HOME honoured)."
  []
  (str (fs/path (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                    (str (fs/path (System/getProperty "user.home") ".config")))
                "systemd" "user" unit-name)))

(defn render
  "The unit text for `bb` and `hub-dir`."
  [{:keys [bb hub-dir]}]
  (when (or (str/blank? bb) (str/blank? hub-dir))
    (throw (ex-info "systemd unit needs the bb path and the hub directory" {:bb bb :hub-dir hub-dir})))
  (-> (slurp (io/resource unit-resource))
      (str/replace "{{BB}}" bb)
      (str/replace "{{HUB_DIR}}" hub-dir)))

(defn next-steps
  "What the owner runs after the file exists (never run by the hub)."
  [path]
  [(str "# unit written to " path)
   "systemctl --user daemon-reload"
   "systemctl --user enable --now clogem-hub      # Ask-first: enabling a unit"
   "systemctl --user status clogem-hub"
   "loginctl enable-linger $USER                   # [DECISION] keep the user manager alive without a login session"
   "# WSL2 idle shutdown ([DECISION], in %UserProfile%\\.wslconfig): [wsl2] vmIdleTimeout=<ms> (default 60000); [general] instanceIdleTimeout=<ms> (default 15000, -1 disables)"])

(defn install!
  "Write the rendered unit to `path` (default `unit-path`). Returns
   {:path path :next-steps [lines]}. Overwrites an existing file: the unit
   is generated, never hand-edited."
  ([] (install! {}))
  ([{:keys [path bb hub-dir]}]
   (let [path (or path (unit-path))
         text (render {:bb (or bb (bb-path)) :hub-dir (or hub-dir (clogem.hub.systemd/hub-dir))})]
     (fs/create-dirs (fs/parent path))
     (spit path text)
     {:path path :next-steps (next-steps path)})))
