(ns issues.query
  "Pure filtering, sorting and selection over issue maps.

  Works on issues from one project or many. Cross-project functions rely on
  `:project` being set on each issue, as `issues.project/read-project`
  does; `:blocked-by` ids are always resolved within the issue's own
  project."
  (:require [clojure.string :as str]
            [issues.issue :as issue]))

(def status-rank
  "Status -> position in the lifecycle, for sorting."
  (zipmap issue/statuses (range)))

(def priority-rank
  "Priority -> rank, `:p0` first."
  (zipmap issue/priorities (range)))

(defn open?
  "True when ISSUE's status counts as still-open work."
  [issue]
  (contains? issue/open-statuses (:status issue)))

(defn matches?
  "True when ISSUE satisfies every non-nil criterion in CRITERIA: `:status`,
  `:type`, `:priority` by equality, `:tag` by membership in `:tags`, and
  `:text` as a case-insensitive substring of the title."
  [issue {:keys [status type priority tag text]}]
  (and (or (nil? status) (= status (:status issue)))
       (or (nil? type) (= type (:type issue)))
       (or (nil? priority) (= priority (:priority issue)))
       (or (nil? tag) (contains? (set (:tags issue)) tag))
       (or (nil? text)
           (str/includes? (str/lower-case (str (:title issue))) (str/lower-case text)))))

(defn filter-issues
  "The issues in ISSUES that satisfy CRITERIA (see `matches?`)."
  [issues criteria]
  (filterv #(matches? % criteria) issues))

(defn- rank-priority [issue] (get priority-rank (:priority issue) 99))
(defn- rank-status [issue] (get status-rank (:status issue) 99))
(defn- project-key [issue] (str (:project issue)))

(defn by-priority
  "ISSUES sorted by priority (`:p0` first), then project, then id."
  [issues]
  (vec (sort-by (juxt rank-priority project-key :id) issues)))

(defn sort-issues
  "ISSUES sorted by lifecycle position, then priority, then project, then
  id."
  [issues]
  (vec (sort-by (juxt rank-status rank-priority project-key :id) issues)))

(defn index-issues
  "Map of `[project id]` -> issue over ISSUES, for blocker lookups."
  [issues]
  (into {} (map (juxt (juxt :project :id) identity)) issues))

(defn blocked?
  "True when any of ISSUE's blockers, looked up in INDEX (from
  `index-issues`) within the same project, is still open. Blockers that are
  done, dropped, or missing do not block; a missing one is a doctor
  finding, not a reason to stall."
  [issue index]
  (boolean (some (fn [blocker]
                   (some-> (get index [(:project issue) blocker]) open?))
                 (:blocked-by issue))))

(defn next-issue
  "The highest-priority `:ready` issue in ISSUES that no open issue blocks,
  or nil."
  [issues]
  (let [index (index-issues issues)]
    (->> issues
         (filter #(= :ready (:status %)))
         (remove #(blocked? % index))
         by-priority
         first)))

(defn inbox
  "The `:inbox` issues in ISSUES, by priority."
  [issues]
  (by-priority (filter #(= :inbox (:status %)) issues)))

(defn counts
  "Number of issues per status (every status present, zero when absent)
  plus `:total`."
  [issues]
  (let [freq (frequencies (map :status issues))]
    (-> (into {} (map (fn [s] [s (get freq s 0)])) issue/statuses)
        (assoc :total (count issues)))))

(defn attention
  "What needs whose attention: `:kira` (in review), `:claude` (inbox and
  ready), and `:blocked`, each by priority."
  [issues]
  (let [with-status (fn [pred] (by-priority (filter #(pred (:status %)) issues)))]
    {:kira (with-status #{:review})
     :claude (with-status #{:inbox :ready})
     :blocked (with-status #{:blocked})}))
