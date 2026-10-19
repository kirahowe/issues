(ns issues.review
  "Kira's verdicts on an issue in review: approve it (done), request
  changes (back to ready, so the agent picks it up again), or reject it
  (dropped). Each verdict can carry a note, appended as a dated line to the
  `## Log` section of the issue's details file so the agent sees why.

  `append-log` is pure; the verdict functions write through `issues.store`
  and regenerate the project's README index."
  (:require [clojure.string :as str]
            [issues.issue :as issue]
            [issues.store :as store]))

(def verdicts
  "Verdict -> the status it moves an issue to, the word its log line uses,
  and whether it needs a note."
  {:approve {:status :done :label "approved" :note? false}
   :request-changes {:status :ready :label "changes requested" :note? true}
   :reject {:status :dropped :label "rejected" :note? false}})

(defn- heading? [line] (re-find #"^#{1,2}\s" line))
(defn- log-heading? [line] (re-matches #"^##\s+Log\s*$" line))

(defn append-log
  "TEXT (details markdown) with LINE added as the last entry of its
  `## Log` section, which is created at the end when absent."
  [text line]
  (let [lines (str/split-lines (or text ""))
        at (first (keep-indexed (fn [i l] (when (log-heading? l) i)) lines))]
    (if-not at
      (str (str/trimr (or text "")) (when-not (str/blank? text) "\n\n")
           "## Log\n\n" line "\n")
      (let [end (or (first (keep-indexed (fn [i l] (when (and (> i at) (heading? l)) i))
                                         lines))
                    (count lines))
            body (->> (subvec (vec lines) (inc at) end)
                      reverse (drop-while str/blank?) reverse)
            section (concat [(nth lines at) ""] (drop-while str/blank? body) [line])
            after (drop end lines)]
        (str (str/join "\n" (concat (take at lines) section
                                    (when (seq after) (cons "" after))))
             "\n")))))

(defn log-line
  "The details log entry for VERDICT with NOTE on TODAY."
  [verdict note today]
  (str "- " today " " (get-in verdicts [verdict :label])
       (when-not (str/blank? note) (str ": " (str/trim note)))))

(defn apply-verdict!
  "Record VERDICT (a key of `verdicts`) on ISSUE, which must carry `:dir`,
  `:id` and `:project` as read by `issues.project/read-project`. A
  non-blank NOTE goes into the details log, creating the details file if
  needed. Returns the updated issue. Throws `ex-info` with `:issues/exit 1`
  when the verdict needs a note and NOTE is blank."
  [issue verdict note today]
  (let [{:keys [status note?]} (get verdicts verdict)]
    (when (and note? (str/blank? note))
      (throw (ex-info (str (name verdict) " needs a note") {:issues/exit 1})))
    (let [dir (:dir issue)
          updated (store/update! dir (:id issue)
                                 #(issue/assoc-field % :status status today))]
      (when-not (str/blank? note)
        (let [with-details (store/ensure-details! updated)]
          (spit (store/details-path with-details)
                (append-log (store/read-details with-details)
                            (log-line verdict note today)))))
      (store/write-index! dir (:project issue) (store/read-all dir))
      (merge issue (store/find-issue dir (:id issue))))))
