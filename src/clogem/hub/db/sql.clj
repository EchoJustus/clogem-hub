;; © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
;;
;; This program and the accompanying materials are made available under the
;; terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0
;;
;; SPDX-License-Identifier: EPL-2.0

(ns clogem.hub.db.sql
  "Shape checks shared by the writer, the reader and the migrator. They
   guard against mistakes in module code (a statement smuggling transaction
   control or a pragma past the preambles), not against hostile modules,
   which run in-process anyway. Statement text is never interpreted beyond
   its first word."
  (:require [clojure.string :as str]))

(defn strip-leading-comments
  "`s` without leading whitespace, `-- …` lines and `/* … */` blocks."
  [s]
  (str/replace s #"(?s)\A(?:\s+|--[^\n]*(?:\n|\z)|/\*.*?\*/)+" ""))

(defn leading-word
  "The first keyword of a statement, upper-cased (\"\" when none)."
  [s]
  (-> (or (re-find #"\A[A-Za-z]+" (strip-leading-comments s)) "")
      str/upper-case))

(defn strip-trailing-semicolons
  "`s` without trailing whitespace and semicolons."
  [s]
  (str/replace s #"(?s)[\s;]+\z" ""))

(defn single-statement?
  "True when `s` holds one statement: no ';' other than trailing ones.
   A ';' inside a string literal also fails; values belong in parameters."
  [s]
  (not (str/includes? (strip-trailing-semicolons s) ";")))

(defn split-statements
  "The non-blank pieces of a multi-statement string, split on ';'. Good
   enough for keyword checks on trusted migration files."
  [s]
  (->> (str/split s #";") (map strip-leading-comments) (remove str/blank?)))

(def transaction-control
  "Words a statement may not start with: the writer owns transactions,
   pragmas and the set of attached databases."
  #{"BEGIN" "COMMIT" "ROLLBACK" "END" "SAVEPOINT" "RELEASE" "ATTACH" "DETACH" "PRAGMA" "VACUUM"})

(def read-only-words
  "Words a read-only query may start with."
  #{"SELECT" "WITH" "VALUES" "EXPLAIN"})

(defn statement-problems
  "Problems with one write statement (a string or [sql & params]). With
   `multi?` several ';'-separated statements are allowed (migration files)
   and each is checked for its leading word."
  ([stmt] (statement-problems stmt false))
  ([stmt multi?]
   (let [text (if (vector? stmt) (first stmt) stmt)]
     (cond
       (not (string? text)) ["must be a string or [sql & params]"]
       (str/blank? text) ["is blank"]
       (and (not multi?) (not (single-statement? text))) ["holds more than one statement; ';' may only end it"]
       :else (vec (for [piece (if multi? (split-statements text) [text])
                        :let [w (leading-word piece)]
                        :when (contains? transaction-control w)]
                    (str "may not start with " w)))))))

(defn query-problems
  "Problems with one read-only query (a string or [sql & params])."
  [q]
  (let [text (if (vector? q) (first q) q)]
    (cond
      (not (string? text)) ["must be a string or [sql & params]"]
      (str/blank? text) ["is blank"]
      (not (single-statement? text)) ["holds more than one statement; ';' may only end it"]
      (not (contains? read-only-words (leading-word text)))
      [(str "must start with one of " (str/join ", " (sort read-only-words)) ", not " (pr-str (leading-word text)))]
      :else [])))
