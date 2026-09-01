(ns issues.snapshot
  "The whole cross-project picture as one data value: every discovered
  project with every issue and problem, plus candidates and shadowed
  copies. Control-plane commands and analyzers both consume this rather
  than touching the filesystem themselves."
  (:require [issues.discover :as discover]
            [issues.project :as project]
            [issues.store :as store]))

(defn now-iso
  "The current time as an ISO-8601 instant with second precision."
  []
  (str (java.time.Instant/ofEpochSecond (quot (System/currentTimeMillis) 1000))))

(defn- with-details
  [issue]
  (assoc issue :details-text (store/read-details issue)))

(defn build
  "Discover projects per CONFIG and read each one. OPTS: `:with-details?`
  inlines every issue's details file as `:details-text`."
  [config {:keys [with-details?]}]
  (let [{:keys [projects shadowed candidates warnings]} (discover/discover config)
        projects (mapv (fn [p]
                         (cond-> (merge p (project/read-project p))
                           with-details? (update :issues #(mapv with-details %))))
                       projects)]
    {:scanned-at (now-iso)
     :roots (:roots config)
     :projects projects
     :candidates candidates
     :shadowed shadowed
     :warnings warnings}))

(defn all-issues
  "Every issue in SNAP, across projects."
  [snap]
  (vec (mapcat :issues (:projects snap))))

(defn index-by-ref
  "Map of `project#id` ref -> issue over SNAP."
  [snap]
  (into {} (map (juxt :ref identity)) (all-issues snap)))

(defn cross-problems
  "Problems only visible across projects: `:related` refs that resolve to
  nothing, and shadowed project copies."
  [snap]
  (let [index (index-by-ref snap)
        dangling (for [issue (all-issues snap)
                       r (:related issue)
                       :when (not (contains? index r))]
                   {:kind :dangling-related :severity :warning :ref (:ref issue) :related r
                    :msg (str (:ref issue) " relates to " r ", which does not exist")})
        shadowed (for [{:keys [id path winner]} (:shadowed snap)]
                   {:kind :shadowed-project :severity :warning :id id :path path
                    :msg (str "project " id " at " path " is shadowed by " winner)})]
    (vec (concat dangling shadowed))))

(defn all-problems
  "Every project's problems, each tagged with `:project`, followed by
  `cross-problems`."
  [snap]
  (vec (concat (for [p (:projects snap)
                     problem (:problems p)]
                 (assoc problem :project (:id p)))
               (cross-problems snap))))
