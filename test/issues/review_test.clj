(ns issues.review-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.project :as project]
            [issues.review :as review]
            [issues.store :as store]
            [issues.test-util :as test-util]))

(deftest append-log-places-the-line-in-the-log-section
  (testing "empty log section at the end"
    (is (= "# T\n\n## Plan\n\n## Log\n\n- x\n"
           (review/append-log "# T\n\n## Plan\n\n## Log\n" "- x"))))
  (testing "after existing entries, before the next section"
    (is (= "## Log\n\n- a\n- x\n\n## After\n\nmore\n"
           (review/append-log "## Log\n\n- a\n\n\n## After\n\nmore\n" "- x"))))
  (testing "a level-three heading stays inside the section"
    (is (= "## Log\n\n### Day\n- a\n- x\n"
           (review/append-log "## Log\n\n### Day\n- a\n" "- x"))))
  (testing "no log section"
    (is (= "# T\n\nprose\n\n## Log\n\n- x\n" (review/append-log "# T\n\nprose\n" "- x"))))
  (testing "no text at all"
    (is (= "## Log\n\n- x\n" (review/append-log nil "- x")))))

(deftest log-line
  (is (= "- 2026-10-19 approved" (review/log-line :approve "  " "2026-10-19")))
  (is (= "- 2026-10-19 changes requested: add tests"
         (review/log-line :request-changes " add tests " "2026-10-19"))))

(defn- in-review
  "Issue N of the canonical fixture at TMP, moved to review and read back
  the way the TUI sees it."
  [tmp n]
  (let [dir (str (fs/path tmp ".issues"))]
    (store/update! dir n #(assoc % :status :review))
    (->> (:issues (project/read-project {:issues-dir dir}))
         (filter #(= n (:id %)))
         first)))

(deftest verdicts
  (test-util/with-temp [tmp "canonical"]
    (testing "approve without a note leaves the details alone"
      (let [x (in-review tmp 1)
            before (store/read-details x)
            done (review/apply-verdict! x :approve "" "2026-10-19")]
        (is (= :done (:status done)))
        (is (= "2026-10-19" (:updated done)))
        (is (= "canon#1" (:ref done)))
        (is (= before (store/read-details done)))
        (is (str/includes? (slurp (str (fs/path tmp ".issues" "README.md"))) "## Done"))))
    (testing "request changes goes back to ready with the note logged"
      (let [back (review/apply-verdict! (in-review tmp 1) :request-changes "cover the edge"
                                        "2026-10-19")]
        (is (= :ready (:status back)))
        (is (str/includes? (store/read-details back)
                           "- 2026-10-19 changes requested: cover the edge"))))
    (testing "request changes needs a note"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a note"
                            (review/apply-verdict! (in-review tmp 1) :request-changes " "
                                                   "2026-10-19"))))
    (testing "reject with a reason creates the details file"
      (let [x (in-review tmp 3)
            dropped (review/apply-verdict! x :reject "not worth it" "2026-10-19")]
        (is (nil? (:details x)))
        (is (= :dropped (:status dropped)))
        (is (= "3-third-issue.md" (:details dropped)))
        (is (str/ends-with? (store/read-details dropped)
                            "## Log\n\n- 2026-10-19 rejected: not worth it\n"))))))
