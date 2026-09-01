(ns issues.analyze
  "The intelligence-layer seam. An analyzer is a method of `analyze`,
  keyed by kind, that takes the whole snapshot and returns insight maps:

      {:kind :duplicate :analyzer :duplicate-titles
       :issues [\"secrets#12\" \"clj-llm#3\"] :score 0.75
       :note \"shared: age backend\" :found-at \"2026-09-01T17:40:00Z\"}

  v0 ships one deterministic analyzer. Embedding- or LLM-backed ones add
  a method here and nothing else changes: same input, same output shape,
  same persistence."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [issues.config :as config]
            [issues.query :as query]
            [issues.snapshot :as snapshot]))

(defmulti analyze
  "Run the analyzer KIND over SNAPSHOT with OPTS, returning a vector of
  insight maps."
  (fn [kind _snapshot _opts] kind))

(def kinds
  "Every analyzer `run-all` runs, in order."
  [:duplicate-titles])

(def stopwords
  "Words that carry no signal for title similarity."
  #{"a" "an" "the" "to" "of" "for" "and" "in" "on" "with" "from" "is" "be" "it"
    "as" "at" "by" "or" "into" "when" "that"})

(defn tokens
  "The set of meaningful lower-cased words in TITLE: split on anything that
  is not a letter or digit, dropping stopwords and single characters."
  [title]
  (->> (str/split (str/lower-case (str title)) #"[^a-z0-9]+")
       (remove #(< (count %) 2))
       (remove stopwords)
       set))

(defn jaccard
  "Jaccard similarity of sets A and B: intersection over union, 0.0 when
  either is empty."
  [a b]
  (if (or (empty? a) (empty? b))
    0.0
    (double (/ (count (set/intersection a b))
               (count (set/union a b))))))

(defmethod analyze :duplicate-titles
  [_ snap {:keys [threshold include-closed?] :or {threshold 0.5}}]
  (let [issues (cond->> (snapshot/all-issues snap)
                 (not include-closed?) (filter query/open?))
        items (vec (for [issue issues
                         :let [t (tokens (:title issue))]
                         :when (>= (count t) 2)]
                     {:issue issue :tokens t}))
        pairs (for [x (range (count items))
                    y (range (inc x) (count items))
                    :let [a (items x)
                          b (items y)
                          score (jaccard (:tokens a) (:tokens b))]
                    :when (>= score threshold)]
                {:kind :duplicate
                 :analyzer :duplicate-titles
                 :issues [(:ref (:issue a)) (:ref (:issue b))]
                 :score (/ (Math/round (* 100 score)) 100.0)
                 :note (str "shared: "
                            (str/join " " (sort (set/intersection (:tokens a) (:tokens b)))))
                 :found-at (:scanned-at snap)})]
    (vec (sort-by (juxt (comp - :score) :issues) pairs))))

(defn run-all
  "Every analyzer in `kinds` over SNAP, concatenated."
  [snap opts]
  (vec (mapcat #(analyze % snap opts) kinds)))

(defn insights-path
  "Where insights persist by default: `<data-dir>/insights.edn`."
  []
  (str (fs/path (config/data-dir) "insights.edn")))

(defn load-insights
  "The insights saved at PATH, or `[]` when there are none."
  [path]
  (if (fs/exists? path)
    (vec (edn/read-string (slurp path)))
    []))

(defn save-insights!
  "Write INSIGHTS to PATH, creating parent directories as needed."
  [path insights]
  (fs/create-dirs (fs/parent path))
  (spit path (with-out-str (pprint/pprint insights))))
