(ns issues.render
  "Turning command results into text: aligned tables for a terminal, EDN
  and JSON for programs. A result is `{:kind kw :data any}`; `human`
  dispatches on `:kind`, the data formats ignore it."
  (:require [cheshire.core :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn ->edn
  "X pretty-printed as EDN."
  [x]
  (with-out-str (pprint/pprint x)))

(defn ->json
  "X as pretty JSON. Keywords become their names, sets become arrays."
  [x]
  (str (json/generate-string x {:pretty true}) "\n"))

(defn table
  "Left-aligned columns separated by two spaces. HEADERS is a vector of
  strings; ROWS a seq of vectors rendered with `str`. Ends with a newline."
  [headers rows]
  (let [rows (map #(mapv str %) rows)
        widths (reduce (fn [ws row] (mapv #(max %1 (count %2)) ws row))
                       (mapv count headers)
                       rows)
        pad (fn [w cell] (format (str "%-" (max 1 w) "s") cell))
        fmt-row (fn [row] (str/trimr (str/join "  " (map pad widths row))))]
    (str (str/join "\n" (map fmt-row (cons headers rows))) "\n")))

(defn- kw-name [v] (if (keyword? v) (name v) (str v)))
(defn- pri [issue] (some-> (:priority issue) name str/upper-case))
(defn- set-cell [s] (if (seq s) (str/join ", " (sort-by str s)) "-"))

(defn issue-row
  "One table row for ISSUE; the first cell is the cross-project ref when
  ALL? is true, else the bare id."
  [issue all?]
  [(if all? (:ref issue) (:id issue))
   (kw-name (:status issue))
   (pri issue)
   (kw-name (:type issue))
   (:title issue)])

(defn issue-table
  "ISSUES as a table, or `no issues` when empty."
  [issues all?]
  (if (empty? issues)
    "no issues\n"
    (table [(if all? "REF" "ID") "STATUS" "PRI" "TYPE" "TITLE"]
           (map #(issue-row % all?) issues))))

(defn- count-row
  [{:keys [project counts]}]
  (into [project] (map counts [:inbox :ready :in-progress :review :blocked :done :dropped :total])))

(defn counts-table
  "ROWS of `{:project :counts}` as one table."
  [rows]
  (table ["PROJECT" "INBOX" "READY" "IN-PROGRESS" "REVIEW" "BLOCKED" "DONE" "DROPPED" "TOTAL"]
         (map count-row rows)))

(defmulti human
  "Render RESULT for a terminal, dispatching on its `:kind`."
  :kind)

(defmethod human :default [{:keys [data]}]
  (->edn data))

(defmethod human :message [{:keys [data]}]
  (str data (when-not (str/ends-with? (str data) "\n") "\n")))

(defmethod human :issue-list [{:keys [data]}]
  (issue-table (:issues data) (:all? data)))

(defmethod human :issue [{:keys [data]}]
  (let [issue data
        line (fn [label value] (format "%-11s %s\n" label value))]
    (str (or (:ref issue) (:id issue)) "  " (:title issue) "\n"
         (line "status" (kw-name (:status issue)))
         (line "type" (kw-name (:type issue)))
         (line "priority" (pri issue))
         (line "created" (:created issue))
         (line "updated" (:updated issue))
         (line "tags" (set-cell (:tags issue)))
         (line "blocked-by" (set-cell (:blocked-by issue)))
         (line "related" (set-cell (:related issue)))
         (line "details" (or (:details issue) "-"))
         (line "file" (:file issue))
         (when-let [text (:details-text issue)]
           (str "\n" text)))))

(defmethod human :updated [{:keys [data]}]
  (str (or (:ref data) (:id data)) "  " (kw-name (:status data)) "  " (:title data) "\n"))

(defmethod human :next [{:keys [data]}]
  (if-let [issue (:issue data)]
    (issue-table [issue] (:all? data))
    "nothing ready\n"))

(defmethod human :counts [{:keys [data]}]
  (counts-table data))

(defmethod human :projects [{:keys [data]}]
  (let [{:keys [projects candidates shadowed warnings]} data]
    (str (if (empty? projects)
           "no projects\n"
           (table ["PROJECT" "OPEN" "TOTAL" "PATH"]
                  (map (fn [{:keys [id counts path]}]
                         [id (- (:total counts) (:done counts) (:dropped counts))
                          (:total counts) path])
                       projects)))
         (when (seq candidates)
           (str "\ncandidates (no .issues/ yet):\n"
                (table ["ID" "VCS" "PATH"]
                       (map (fn [{:keys [id vcs path]}] [id (kw-name vcs) path]) candidates))))
         (when (seq shadowed)
           (str "\nshadowed copies:\n"
                (str/join (map (fn [{:keys [id path winner]}]
                                 (str "  " id "  " path "  (shadowed by " winner ")\n"))
                               shadowed))))
         (when (seq warnings)
           (str "\n" (str/join (map #(str "warning: " % "\n") warnings)))))))

(defmethod human :attention [{:keys [data]}]
  (let [section (fn [title issues]
                  (str title "\n" (issue-table issues true)))]
    (str (section "needs Kira (review):" (:kira data))
         "\n" (section "needs Claude (inbox, ready):" (:claude data))
         "\n" (section "blocked:" (:blocked data)))))

(defn- problem-line
  [{:keys [severity project id kind msg]}]
  (str (kw-name severity) "  "
       (cond
         (and project id) (str project "#" id)
         project project
         id (str "#" id)
         :else "-")
       "  " (kw-name kind) ": " msg "\n"))

(defmethod human :doctor [{:keys [data]}]
  (let [problems (:problems data)
        errors (count (filter #(= :error (:severity %)) problems))
        warnings (count (filter #(= :warning (:severity %)) problems))]
    (if (empty? problems)
      "no problems\n"
      (str (str/join (map problem-line problems))
           (format "%d error(s), %d warning(s)\n" errors warnings)))))

(defmethod human :config [{:keys [data]}]
  (str "config: " (:path data) (if (:exists? data) "" " (absent, using defaults)") "\n"
       (->edn (:config data))))

(defn emit
  "RESULT as a string in FORMAT (`:human`, `:edn`, or `:json`)."
  [result format]
  (case format
    :json (->json (:data result))
    :edn (->edn (:data result))
    (human result)))
