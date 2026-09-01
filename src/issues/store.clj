(ns issues.store
  "IO over one project's `.issues/` directory: reading, writing, and
  listing issue files, plus the generated `README.md` index.

  Every path taken or returned by a function here is a plain string, never
  a `java.nio.file.Path`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [issues.issue :as issue]))

(def file-pattern
  "Matches an issue file name: a leading id, a `-`, anything, `.edn`."
  #"^(\d+)-.*\.edn$")

(defn file-id
  "The leading numeric id in PATH's file name, or nil when the name doesn't
  match `file-pattern`."
  [path]
  (when-let [[_ n] (re-matches file-pattern (str (fs/file-name (str path))))]
    (parse-long n)))

(defn issue-files
  "Absolute path strings of the issue `.edn` files directly in DIR, sorted
  numerically by id. `[]` for a missing or empty DIR."
  [dir]
  (->> (fs/glob dir "*.edn")
       (map str)
       (filter file-id)
       (sort-by file-id)
       (mapv #(str (fs/absolutize %)))))

(defn read-issue
  "Read and parse the issue file at PATH. Adds `:file` and `:dir` (absolute
  path strings). The file name's id is authoritative: it always becomes
  `:id`, and a mismatch against the parsed content's `:id` is recorded as
  an `:id-mismatch` problem. When the file is unreadable, `:problems` is
  left as-is (no validation runs); otherwise `:problems` is
  `(issue/validate issue)` plus any id-mismatch problem. `:problems` is
  always a vector."
  [path]
  (let [abs (str (fs/absolutize path))
        fid (file-id abs)
        parsed (issue/parse-string (slurp abs))
        dir (str (fs/absolutize (fs/parent abs)))
        unreadable? (some #(= :unreadable (:kind %)) (:problems parsed))]
    (if unreadable?
      (assoc parsed :id fid :file abs :dir dir :problems (vec (:problems parsed)))
      (let [mismatch? (and (contains? parsed :id) (not= (:id parsed) fid))
            mismatch-problem (when mismatch?
                               {:kind :id-mismatch :severity :error :field :id
                                :msg (str "file name id " fid
                                          " does not match content id " (:id parsed))})
            candidate (assoc parsed :id fid :file abs :dir dir)
            problems (cond-> (issue/validate candidate)
                       mismatch-problem (conj mismatch-problem))]
        (assoc candidate :problems (vec problems))))))

(defn read-all
  "Every issue in DIR, read via `read-issue` and sorted by `:id`."
  [dir]
  (->> (issue-files dir)
       (map read-issue)
       (sort-by :id)
       vec))

(defn find-issue
  "The issue in DIR whose file name id is ID, or nil. Matches by file name
  id only, so a changed slug never breaks lookup."
  [dir id]
  (when-let [path (first (filter #(= id (file-id %)) (issue-files dir)))]
    (read-issue path)))

(defn next-id
  "The next unused issue id in DIR: one past the highest existing file id,
  or 1 for an empty DIR. Gaps in the sequence are ignored."
  [dir]
  (inc (apply max 0 (map file-id (issue-files dir)))))

(defn slug
  "A short, URL-safe slug derived from TITLE: lower-cased, every run of
  characters outside `[a-z0-9]` collapsed to a single `-`, leading and
  trailing `-` trimmed, cut to at most 60 characters at a word boundary,
  and `\"issue\"` when nothing is left."
  [title]
  (let [collapsed (-> title
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"^-+|-+$" ""))
        cut (if (<= (count collapsed) 60)
              collapsed
              ;; Cut at a word boundary so a long title does not end mid-word.
              (let [head (subs collapsed 0 60)
                    at (str/last-index-of head "-")]
                (if (and at (pos? at)) (subs head 0 at) head)))]
    (if (str/blank? cut) "issue" cut)))

(defn issue-filename
  "The on-disk file name for an issue ID with TITLE."
  [id title]
  (str id "-" (slug title) ".edn"))

(defn write-issue!
  "Render ISSUE and spit it to `(:file issue)`. Returns ISSUE."
  [issue]
  (spit (:file issue) (issue/render-string issue))
  issue)

(defn- attempt-create!
  "Try once to exclusively create an issue file for FIELDS (dated TODAY) in
  DIR, using a freshly computed next id. Returns the new issue map (with
  `:file`/`:dir` set, content not yet written) on success; throws
  `java.nio.file.FileAlreadyExistsException` on an id collision."
  [dir fields today]
  (let [id (next-id dir)
        filename (issue-filename id (:title fields))
        path (str (fs/path dir filename))]
    (fs/create-file path)
    (assoc (issue/new-issue (assoc fields :id id :today today))
           :file path :dir dir)))

(defn create!
  "Create a new issue in DIR from FIELDS (`:title` `:type` `:priority`
  `:tags`), dated TODAY. Retries once on an id collision before giving up
  with an `ex-info` carrying `:issues/exit 1`. Only the `.edn` file is
  created. Returns the issue with `:file`/`:dir` set."
  [dir fields today]
  (let [dir (str (fs/absolutize dir))
        issue (try
                (attempt-create! dir fields today)
                (catch java.nio.file.FileAlreadyExistsException _
                  (try
                    (attempt-create! dir fields today)
                    (catch java.nio.file.FileAlreadyExistsException _
                      (throw (ex-info "could not allocate an issue id"
                                      {:issues/exit 1 :dir dir}))))))]
    (write-issue! issue)))

(defn update!
  "Find the issue ID in DIR, apply F to it, write the result, and return
  it. Throws `ex-info` with `:issues/exit 1` when no such issue exists."
  [dir id f]
  (let [issue (or (find-issue dir id)
                  (throw (ex-info (str "no issue " id " in " dir) {:issues/exit 1})))]
    (write-issue! (f issue))))

(defn details-filename
  "ISSUE's edn file name with `.edn` replaced by `.md`."
  [issue]
  (str/replace (str (fs/file-name (:file issue))) #"\.edn$" ".md"))

(defn details-path
  "Absolute path string to ISSUE's details file, or nil when `:details` is
  absent."
  [issue]
  (when (:details issue)
    (str (fs/path (:dir issue) (:details issue)))))

(defn ensure-details!
  "Ensure ISSUE has a details file: link one in (via `details-filename`) if
  it doesn't already have one, and create the file from
  `issue/details-template` if it doesn't yet exist. Never overwrites an
  existing details file or rewrites the issue file when nothing changed.
  Returns the (possibly updated) issue."
  [issue]
  (let [issue (if (:details issue)
                issue
                (write-issue! (assoc issue :details (details-filename issue))))
        path (details-path issue)]
    (when-not (fs/exists? path)
      (spit path (issue/details-template issue)))
    issue))

(defn read-details
  "The contents of ISSUE's details file, or nil when it has none or the
  file doesn't exist."
  [issue]
  (let [path (details-path issue)]
    (when (and path (fs/exists? path))
      (slurp path))))

(defn dir-problems
  "Cross-issue problems over ISSUES: duplicate ids, `:blocked-by` ids that
  don't resolve to any issue in ISSUES, and issues whose `:details` file is
  missing. Pure except for that last file-existence check."
  [issues]
  (let [by-id (frequencies (map :id issues))
        dup-ids (->> by-id (filter #(> (val %) 1)) keys sort)
        known-ids (set (keys by-id))
        dup-problems
        (for [id dup-ids]
          {:kind :duplicate-id :severity :error :id id
           :msg (str "id " id " is used by more than one issue")})
        dangling-problems
        (for [issue issues
              blocker (:blocked-by issue)
              :when (not (contains? known-ids blocker))]
          {:kind :dangling-blocked-by :severity :error :id (:id issue) :blocker blocker
           :msg (str "issue " (:id issue) " is blocked by " blocker ", which does not exist")})
        missing-details-problems
        (for [issue issues
              :when (and (:details issue) (not (fs/exists? (details-path issue))))]
          {:kind :missing-details :severity :warning :id (:id issue)
           :msg (str "issue " (:id issue) " links to missing details file " (:details issue))})]
    (vec (concat dup-problems dangling-problems missing-details-problems))))

(def index-marker
  "The first line of a generated `README.md`, used to detect ownership."
  "<!-- generated by issues; do not edit -->")

(defn- escape-pipe
  [s]
  (str/replace s "|" "\\|"))

(defn- issue-link-target
  "The markdown link target for ISSUE: its details file when linked,
  otherwise its edn file name."
  [issue]
  (or (:details issue) (str (fs/file-name (:file issue)))))

(def ^:private priority-rank
  "Priority keyword -> sort rank, `:p0` first."
  (zipmap issue/priorities (range)))

(defn- sorted-by-priority
  "ISSUES sorted by priority (`:p0` first), then id."
  [issues]
  (sort-by (juxt (comp priority-rank :priority) :id) issues))

(defn- table-row
  [issue]
  (str "| [" (:id issue) "](" (issue-link-target issue) ") | "
       (escape-pipe (:title issue)) " | "
       (name (:type issue)) " | "
       (str/upper-case (name (:priority issue))) " |"))

(defn- table-block
  [issues]
  (str "| ID | Title | Type | Priority |\n"
       "| --- | --- | --- | --- |\n"
       (str/join "\n" (map table-row (sorted-by-priority issues)))))

(defn- status-label
  [status]
  (str/capitalize (str/replace (name status) "-" " ")))

(defn- open-blocks
  "The markdown blocks for the `## Open` section: the heading, then one
  `### Label` heading and table per non-empty open status in
  `issue/statuses` order; just `_none_` when ISSUES is empty."
  [issues]
  (if (empty? issues)
    ["## Open" "_none_"]
    (let [by-status (group-by :status issues)]
      (into ["## Open"]
            (mapcat (fn [status]
                      (when-let [group (seq (get by-status status))]
                        [(str "### " (status-label status)) (table-block group)]))
                    (filter issue/open-statuses issue/statuses))))))

(defn- closed-block
  [heading issues]
  (str "## " heading "\n\n" (if (seq issues) (table-block issues) "_none_")))

(defn index-markdown
  "Deterministic `README.md` markdown for PROJECT-ID's ISSUES: open issues
  grouped by status, then done, then dropped. Issues whose `:status` isn't
  in `issue/statuses` are skipped. Ends with a single trailing newline."
  [project-id issues]
  (let [known (filter #(contains? (set issue/statuses) (:status %)) issues)
        open (filter #(contains? issue/open-statuses (:status %)) known)
        done (filter #(= :done (:status %)) known)
        dropped (filter #(= :dropped (:status %)) known)
        blocks (concat [(str index-marker "\n# " project-id " — issues")]
                       (open-blocks open)
                       [(closed-block "Done" done)]
                       [(closed-block "Dropped" dropped)])]
    (str (str/join "\n\n" blocks) "\n")))

(defn index-state
  "`:missing` when DIR has no `README.md`; `:generated` when its first line
  is `index-marker`; `:foreign` otherwise."
  [dir]
  (let [path (str (fs/path dir "README.md"))]
    (cond
      (not (fs/exists? path)) :missing
      (= index-marker (first (str/split-lines (slurp path)))) :generated
      :else :foreign)))

(defn write-index!
  "Write DIR's generated `README.md` for PROJECT-ID's ISSUES unless a
  foreign (hand-written) one is present. Returns `{:written? bool :state
  kw}`."
  [dir project-id issues]
  (if (= :foreign (index-state dir))
    {:written? false :state :foreign}
    (let [path (str (fs/path dir "README.md"))
          content (index-markdown project-id issues)
          existing (when (fs/exists? path) (slurp path))]
      (if (= existing content)
        {:written? false :state :generated}
        (do (spit path content)
            {:written? true :state :generated})))))
