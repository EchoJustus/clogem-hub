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
   control or a pragma past the preambles, a parameter list that does not
   match its placeholders), not against hostile modules, which run
   in-process anyway. Statement text is scanned, never interpreted beyond
   its keywords: comments and string literals are recognised so that a
   `;` or a `?` inside them does not count."
  (:require [clojure.string :as str]))

(defn- scan
  "Walk `s` once. Returns {:code string-without-comments :placeholders n
   :numbered? bool :unterminated? bool}: `code` keeps string literals and
   replaces every comment with a space; placeholders counts bare `?`
   outside literals; numbered? flags `?NNN` parameters; unterminated? a
   block comment or string literal left open."
  [^String s]
  (let [n (count s)
        sb (StringBuilder.)]
    (loop [i 0 mode :code placeholders 0 numbered? false]
      (if (>= i n)
        {:code (str sb) :placeholders placeholders :numbered? numbered?
         ;; a line comment may end with the text; a block comment or literal may not
         :unterminated? (contains? #{:block :string} mode)}
        (let [c (.charAt s i)
              c2 (when (< (inc i) n) (.charAt s (inc i)))]
          (case mode
            :code (cond
                    (and (= c \-) (= c2 \-)) (recur (+ i 2) :line placeholders numbered?)
                    (and (= c \/) (= c2 \*)) (recur (+ i 2) :block placeholders numbered?)
                    (= c \') (do (.append sb c) (recur (inc i) :string placeholders numbered?))
                    (= c \?) (do (.append sb c)
                                 (recur (inc i) :code (inc placeholders)
                                        (or numbered? (boolean (and c2 (Character/isDigit ^char c2))))))
                    :else (do (.append sb c) (recur (inc i) :code placeholders numbered?)))
            :string (do (.append sb c)
                        (if (= c \')
                          (if (= c2 \') ; escaped quote
                            (do (.append sb c2) (recur (+ i 2) :string placeholders numbered?))
                            (recur (inc i) :code placeholders numbered?))
                          (recur (inc i) :string placeholders numbered?)))
            :line (if (= c \newline)
                    (do (.append sb c) (recur (inc i) :code placeholders numbered?))
                    (recur (inc i) :line placeholders numbered?))
            :block (if (and (= c \*) (= c2 \/))
                     (do (.append sb \space) (recur (+ i 2) :code placeholders numbered?))
                     (recur (inc i) :block placeholders numbered?))))))))

(defn strip-comments
  "`s` with every `-- …` and `/* … */` comment replaced by whitespace;
   string literals are kept."
  [s]
  (:code (scan s)))

(defn strip-leading-comments
  "`s` without leading whitespace and comments."
  [s]
  (str/triml (strip-comments s)))

(defn leading-word
  "The first keyword of a statement, upper-cased (\"\" when none)."
  [s]
  (-> (or (re-find #"\A[A-Za-z]+" (strip-leading-comments s)) "")
      str/upper-case))

(defn- words
  "Upper-cased keywords of `s`, comments removed."
  [s]
  (mapv str/upper-case (re-seq #"[A-Za-z_]+" (strip-comments s))))

(defn strip-trailing-semicolons
  "`s` without trailing whitespace, semicolons and comments."
  [s]
  (str/replace (strip-comments s) #"(?s)[\s;]+\z" ""))

(defn single-statement?
  "True when `s` holds one statement: no ';' other than trailing ones,
   comments and string literals excluded."
  [s]
  (not (str/includes? (strip-trailing-semicolons s) ";")))

(defn split-statements
  "The non-blank pieces of a multi-statement string, comments removed and
   split on ';'. Good enough for keyword checks on trusted migration files."
  [s]
  (->> (str/split (strip-comments s) #";") (map str/trim) (remove str/blank?)))

(defn placeholder-count
  "Bare `?` placeholders outside literals and comments."
  [s]
  (:placeholders (scan s)))

(def transaction-control
  "Words a statement may not start with: the writer owns transactions,
   pragmas (also behind EXPLAIN, which runs flag pragmas at prepare time)
   and the set of attached databases."
  #{"BEGIN" "COMMIT" "ROLLBACK" "END" "SAVEPOINT" "RELEASE" "ATTACH" "DETACH" "PRAGMA" "VACUUM" "EXPLAIN"})

(def migration-control
  "Like `transaction-control` without END: trigger bodies end with it."
  (disj transaction-control "END"))

(def read-only-words
  "Words a read-only query may start with."
  #{"SELECT" "WITH" "VALUES" "EXPLAIN"})

(defn- text-problems
  "Lexical problems shared by statements and queries."
  [text]
  (let [{:keys [unterminated? numbered?]} (scan text)]
    (cond-> []
      unterminated? (conj "has an unterminated comment or string literal")
      numbered? (conj "uses numbered parameters; use plain ? placeholders"))))

(defn- param-problems [stmt text]
  (let [given (if (vector? stmt) (dec (count stmt)) 0)
        wanted (placeholder-count text)]
    (when (not= given wanted)
      [(str "has " wanted " placeholder(s) but " given " parameter(s)")])))

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
       :else
       (let [lexical (text-problems text)]
         (if (seq lexical)
           lexical
           (into (vec (param-problems stmt text))
                 (concat
                  (when (and (not multi?) (not (single-statement? text)))
                    ["holds more than one statement; ';' may only end it"])
                  (for [piece (if multi? (split-statements text) [text])
                        :let [w (leading-word piece)]
                        :when (contains? (if multi? migration-control transaction-control) w)]
                    (str "may not start with " w))))))))))

(defn query-problems
  "Problems with one read-only query (a string or [sql & params])."
  [q]
  (let [text (if (vector? q) (first q) q)]
    (cond
      (not (string? text)) ["must be a string or [sql & params]"]
      (str/blank? text) ["is blank"]
      :else
      (let [lexical (text-problems text)
            ws (words text)
            w (first ws)
            explained (when (= "EXPLAIN" w)
                        (first (drop-while #{"EXPLAIN" "QUERY" "PLAN"} ws)))]
        (cond
          (seq lexical) lexical
          (not (single-statement? text)) ["holds more than one statement; ';' may only end it"]
          (not (contains? read-only-words w))
          [(str "must start with one of " (str/join ", " (sort read-only-words)) ", not " (pr-str (or w "")))]
          (and (= "EXPLAIN" w) (not (contains? #{"SELECT" "WITH" "VALUES"} explained)))
          [(str "EXPLAIN may only precede SELECT, WITH or VALUES, not " (pr-str (or explained "")))]
          :else (vec (param-problems q text)))))))
