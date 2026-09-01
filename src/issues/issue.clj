(ns issues.issue
  "Pure core for issue records: string <-> map, canonical rendering, and
  validation that returns data instead of throwing.

  An issue is a plain map read from (and rendered back to) a `.edn` file in
  a project's `.issues/` directory. Every function here is pure except
  `today`; callers thread a `today` string through instead of touching the
  clock."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def statuses
  "Lifecycle order, from newly filed to terminal."
  [:inbox :ready :in-progress :review :blocked :done :dropped])

(def open-statuses
  "Statuses that count as still-open work."
  #{:inbox :ready :in-progress :review :blocked})

(def types
  "Recognized issue types."
  [:feature :bug :chore :idea])

(def priorities
  "Recognized priorities, most to least urgent."
  [:p0 :p1 :p2 :p3])

(def key-order
  "Order known keys render in. Anything else follows, sorted by name."
  [:id :title :status :type :priority :created :updated :tags :blocked-by
   :related :details])

(def runtime-keys
  "Keys attached to an issue map in memory for bookkeeping; never written to
  disk."
  #{:file :dir :project :ref :problems})

(def ref-pattern
  "A cross-project reference: `project#id`."
  #"^([A-Za-z0-9._-]+)#(\d+)$")

(defn- as-keyword
  "Lower-case and keywordize V if it's a string; otherwise return it as-is."
  [v]
  (if (string? v) (keyword (str/lower-case v)) v))

(defn- as-set
  "Coerce nil or any collection to a set; leave anything else untouched."
  [v]
  (cond
    (nil? v) #{}
    (coll? v) (set v)
    :else v))

(defn normalize
  "Coerce loosely-typed values from a freshly read issue map into their
  canonical types: `:status`/`:type`/`:priority` string values become
  keywords, and `:tags`/`:blocked-by`/`:related` collections (or nil) become
  sets. Absent keys are left absent; every other key is untouched."
  [m]
  (as-> m m
    (reduce (fn [acc k] (cond-> acc (contains? acc k) (update k as-keyword)))
            m [:status :type :priority])
    (reduce (fn [acc k] (cond-> acc (contains? acc k) (update k as-set)))
            m [:tags :blocked-by :related])))

(defn today
  "Today's date as `YYYY-MM-DD`, from the system clock. The only impure
  function in this namespace; every other function takes a `today` argument
  when it needs a date."
  []
  (str (java.time.LocalDate/now)))

(defn parse-string
  "Parse S (the contents of an issue `.edn` file) into a normalized issue
  map. Never throws: unreadable input or a non-map result becomes a map
  with a single `:problems` entry describing the failure."
  [s]
  (try
    (let [v (edn/read-string s)]
      (if (map? v)
        (normalize v)
        {:problems [{:kind :unreadable :severity :error :msg "not a map"}]}))
    (catch Exception e
      {:problems [{:kind :unreadable :severity :error :msg (.getMessage e)}]})))

(defn- format-set
  "Render a set in canonical sorted form: `#{}` when empty, numeric order
  when every element is a number, otherwise sorted by `str`."
  [s]
  (if (empty? s)
    "#{}"
    (let [sorted (if (every? number? s) (sort s) (sort-by str s))]
      (str "#{" (str/join " " (map pr-str sorted)) "}"))))

(defn- render-value
  "Render a single value in canonical form: sets sorted, everything else via
  `pr-str`."
  [v]
  (if (set? v) (format-set v) (pr-str v)))

(defn render-string
  "Render ISSUE to its canonical on-disk EDN form: one `key value` pair per
  line, known keys first in `key-order` order, then any unknown keys sorted
  by name. Runtime keys are never emitted. The result ends with a trailing
  newline, so `(render-string (parse-string s))` round-trips a canonical
  file byte for byte."
  [issue]
  (let [known (set key-order)
        present-known (filter #(contains? issue %) key-order)
        unknown (->> (keys issue)
                     (remove known)
                     (remove runtime-keys)
                     (sort-by name))
        lines (map (fn [k] (str (pr-str k) " " (render-value (get issue k))))
                   (concat present-known unknown))]
    (str "{" (str/join "\n " lines) "}\n")))

(defn- valid-date?
  "True when S is a `YYYY-MM-DD` string that `LocalDate/parse` accepts."
  [s]
  (boolean
   (and (string? s)
        (re-matches #"^\d{4}-\d{2}-\d{2}$" s)
        (try
          (java.time.LocalDate/parse s)
          true
          (catch Exception _ false)))))

(defn- valid-set?
  "True when V is a collection and every element satisfies PRED."
  [v pred]
  (and (coll? v) (every? pred v)))

(defn- ref-str?
  "True when V is a string matching `ref-pattern`."
  [v]
  (and (string? v) (boolean (re-matches ref-pattern v))))

(defn validate
  "Validate ISSUE and return a vector of problem maps
  `{:kind :severity :msg :field}` (`:field` is omitted for `:self-block`).
  Never throws; returns `[]` when ISSUE is valid."
  [issue]
  (let [required [:id :title :status :type :priority :created :updated]
        missing (for [k required :when (not (contains? issue k))]
                  {:kind :missing-field :severity :error :field k
                   :msg (str "missing required field " k)})
        vocab-msg (fn [vocab] (str "must be one of " (str/join ", " (map name vocab))))
        checks
        [(when (and (contains? issue :id) (not (pos-int? (:id issue))))
           {:kind :bad-id :severity :error :field :id
            :msg "id must be a positive integer"})
         (when (and (contains? issue :title)
                    (not (and (string? (:title issue)) (not (str/blank? (:title issue))))))
           {:kind :bad-title :severity :error :field :title
            :msg "title must be a non-blank string"})
         (when (and (contains? issue :status) (not (contains? (set statuses) (:status issue))))
           {:kind :bad-status :severity :error :field :status :msg (vocab-msg statuses)})
         (when (and (contains? issue :type) (not (contains? (set types) (:type issue))))
           {:kind :bad-type :severity :error :field :type :msg (vocab-msg types)})
         (when (and (contains? issue :priority)
                    (not (contains? (set priorities) (:priority issue))))
           {:kind :bad-priority :severity :error :field :priority :msg (vocab-msg priorities)})
         (when (and (contains? issue :created) (not (valid-date? (:created issue))))
           {:kind :bad-date :severity :error :field :created
            :msg "created must be a YYYY-MM-DD date"})
         (when (and (contains? issue :updated) (not (valid-date? (:updated issue))))
           {:kind :bad-date :severity :error :field :updated
            :msg "updated must be a YYYY-MM-DD date"})
         (when (and (contains? issue :tags) (not (valid-set? (:tags issue) string?)))
           {:kind :bad-tags :severity :error :field :tags :msg "tags must be strings"})
         (when (and (contains? issue :blocked-by)
                    (not (valid-set? (:blocked-by issue) pos-int?)))
           {:kind :bad-blocked-by :severity :error :field :blocked-by
            :msg "blocked-by must be positive integers"})
         (when (and (contains? issue :blocked-by) (contains? issue :id)
                    (coll? (:blocked-by issue))
                    (contains? (set (:blocked-by issue)) (:id issue)))
           {:kind :self-block :severity :error :msg "an issue cannot block itself"})
         (when (and (contains? issue :related) (not (valid-set? (:related issue) ref-str?)))
           {:kind :bad-related :severity :error :field :related
            :msg "related must be project#id references"})
         (when (and (contains? issue :details)
                    (not (and (string? (:details issue)) (not (str/blank? (:details issue))))))
           {:kind :bad-details :severity :error :field :details
            :msg "details must be a non-blank string"})]]
    (vec (concat missing (remove nil? checks)))))

(defn new-issue
  "Build a full new issue map from minimal input. `:status` starts at
  `:inbox`; `:type` defaults to `:feature`, `:priority` to `:p2`, `:tags` to
  `#{}`. `:created` and `:updated` are both set to TODAY. `:blocked-by` and
  `:related` start empty; there is no `:details` yet."
  [{:keys [id title type priority tags today]}]
  {:id id
   :title title
   :status :inbox
   :type (or type :feature)
   :priority (or priority :p2)
   :created today
   :updated today
   :tags (set (or tags #{}))
   :blocked-by #{}
   :related #{}})

(defn- csv-parts
  "Split S on commas, trimming whitespace and dropping blank parts."
  [s]
  (->> (str/split s #",")
       (map str/trim)
       (remove str/blank?)))

(defn- coerce-enum
  "Coerce S to a keyword and check it's a member of VOCAB."
  [vocab s]
  (let [kw (keyword (str/lower-case s))]
    (if (contains? (set vocab) kw)
      {:value kw}
      {:error (str "must be one of " (str/join ", " (map name vocab)))})))

(defn- coerce-int-set
  [s]
  (let [parsed (map parse-long (csv-parts s))]
    (if (every? #(and (some? %) (pos? %)) parsed)
      {:value (set parsed)}
      {:error "must be a comma-separated list of positive integers"})))

(defn- coerce-ref-set
  [s]
  (let [parts (csv-parts s)]
    (if (every? #(re-matches ref-pattern %) parts)
      {:value (set parts)}
      {:error "must be a comma-separated list of project#id references"})))

(defn coerce-field
  "Parse CLI string input S for FIELD into `{:value v}` or `{:error msg}`.
  Never throws. Recognizes `:status`, `:type`, `:priority`, `:title`,
  `:tags`, `:blocked-by`, and `:related`; any other field is an error."
  [field s]
  (case field
    :status (coerce-enum statuses s)
    :type (coerce-enum types s)
    :priority (coerce-enum priorities s)
    :title (let [t (str/trim s)]
             (if (str/blank? t)
               {:error "title must not be blank"}
               {:value t}))
    :tags {:value (set (csv-parts s))}
    :blocked-by (coerce-int-set s)
    :related (coerce-ref-set s)
    {:error "unknown field"}))

(defn assoc-field
  "Set FIELD to V on ISSUE and bump `:updated` to TODAY."
  [issue field v today]
  (assoc issue field v :updated today))

(defn ref-string
  "Render a cross-project reference to ID within PROJECT, e.g. `project#12`."
  [project id]
  (str project "#" id))

(defn parse-ref
  "Parse a `project#id` reference string into `{:project s :id n}`, or nil
  when S doesn't match `ref-pattern`."
  [s]
  (when-let [[_ project id] (re-matches ref-pattern s)]
    {:project project :id (parse-long id)}))

(defn details-template
  "The starter contents of a new issue's details markdown file."
  [issue]
  (str "# " (:title issue)
       "\n\n## Context\n\n## Acceptance criteria\n\n- [ ] \n\n## Plan\n\n## Log\n"))
