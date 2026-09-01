(ns issues.store-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.issue :as issue]
            [issues.store :as store]
            [issues.test-util :as test-util]))

(defn- issues-dir
  [tmp]
  (str (fs/path tmp ".issues")))

(def ^:private readme-path
  (fs/path test-util/fixtures-dir "canonical" ".issues" "README.md"))

(deftest file-id-parses-leading-digits
  (is (= 10 (store/file-id "10-x.edn")))
  (is (nil? (store/file-id "README.md")))
  (is (nil? (store/file-id "project.edn")))
  (is (nil? (store/file-id "x-1.edn"))))

(deftest issue-files-behaviour
  (testing "sorts numerically, not lexically"
    (test-util/with-temp [tmp nil]
      (spit (str (fs/path tmp "10-x.edn")) "{}")
      (spit (str (fs/path tmp "9-y.edn")) "{}")
      (is (= [9 10] (map store/file-id (store/issue-files tmp))))))
  (testing "missing dir"
    (test-util/with-temp [tmp nil]
      (is (= [] (store/issue-files (str (fs/path tmp "nope"))))))))

(deftest read-all-fixture
  (test-util/with-temp [tmp "canonical"]
    (let [issues (store/read-all (issues-dir tmp))]
      (is (= [1 2 3] (map :id issues)))
      (doseq [issue issues]
        (is (string? (:file issue)))
        (is (string? (:dir issue)))
        (is (= [] (:problems issue)))
        (is (str/ends-with? (:dir issue) ".issues"))))))

(deftest read-issue-id-mismatch
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          content (issue/render-string
                   {:id 7 :title "Mismatched" :status :ready :type :feature
                    :priority :p2 :created "2026-09-01" :updated "2026-09-01"
                    :tags #{} :blocked-by #{} :related #{}})
          path (str (fs/path dir "5-x.edn"))
          _ (spit path content)
          issue (store/read-issue path)]
      (is (= 5 (:id issue)))
      (is (some #(= :id-mismatch (:kind %)) (:problems issue))))))

(deftest read-issue-unreadable
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          path (str (fs/path dir "4-bad.edn"))
          _ (spit path "{")
          issue (store/read-issue path)]
      (is (= 4 (:id issue)))
      (is (= 1 (count (:problems issue))))
      (is (= :unreadable (:kind (first (:problems issue))))))))

(deftest find-issue-behaviour
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (is (= "Second issue: blocked by #1" (:title (store/find-issue dir 2))))
      (is (nil? (store/find-issue dir 42))))))

(deftest next-id-behaviour
  (test-util/with-temp [tmp "canonical"]
    (is (= 4 (store/next-id (issues-dir tmp)))))
  (test-util/with-temp [tmp nil]
    (is (= 1 (store/next-id tmp))))
  (test-util/with-temp [tmp nil]
    (doseq [id [1 2 7]]
      (spit (str (fs/path tmp (str id "-x.edn"))) "{}"))
    (is (= 8 (store/next-id tmp)))))

(deftest slug-behaviour
  (is (= "add-age-encryption-backend" (store/slug "Add age encryption backend")))
  (is (= "weird-title-12" (store/slug "  Weird!! Title: #12 ")))
  (let [s (store/slug (apply str (repeat 100 "a")))]
    (is (<= (count s) 60))
    (is (not (str/ends-with? s "-"))))
  (is (= "issue" (store/slug "!!!"))))

(deftest create!-basic
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          issue (store/create! dir {:title "New thing"} "2026-09-02")]
      (is (= 4 (:id issue)))
      (is (fs/exists? (str (fs/path dir "4-new-thing.edn"))))
      (is (not (fs/exists? (str (fs/path dir "4-new-thing.md")))))
      (is (= :inbox (:status issue)))
      (is (= "2026-09-02" (:created issue)))
      (is (= "2026-09-02" (:updated issue)))
      (is (string? (:file issue)))
      (is (string? (:dir issue)))
      (let [issue2 (store/create! dir {:title "Another thing"} "2026-09-02")]
        (is (= 5 (:id issue2)))))))

(deftest create!-collision-gives-up-after-one-retry
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (with-redefs [store/next-id (constantly 1)
                    store/slug (constantly "first-issue")]
        (let [ex (try
                   (store/create! dir {:title "X"} "2026-09-02")
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex))
          (is (= 1 (:issues/exit (ex-data ex)))))))))

(deftest create!-collision-succeeds-on-retry
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          calls (atom 0)]
      (with-redefs [store/next-id (fn [_]
                                    (swap! calls inc)
                                    (if (= 1 @calls) 1 4))
                    store/slug (constantly "first-issue")]
        (let [issue (store/create! dir {:title "X"} "2026-09-02")]
          (is (= 4 (:id issue))))))))

(deftest update!-behaviour
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (store/update! dir 1 #(assoc % :status :done :updated "2026-09-02"))
      (is (= :done (:status (store/find-issue dir 1))))
      (let [ex (try
                 (store/update! dir 42 identity)
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= 1 (:issues/exit (ex-data ex))))))))

(deftest ensure-details!-creates-and-links
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          md-path (str (fs/path dir "2-second-issue.md"))
          edn-path (str (fs/path dir "2-second-issue.edn"))
          updated (store/ensure-details! (store/find-issue dir 2))]
      (is (= "2-second-issue.md" (:details updated)))
      (is (fs/exists? md-path))
      (is (str/starts-with? (slurp md-path) "# Second issue: blocked by #1"))
      (let [before-md (slurp md-path)
            before-edn (slurp edn-path)
            _ (store/ensure-details! (store/find-issue dir 2))]
        (is (= before-md (slurp md-path)))
        (is (= before-edn (slurp edn-path)))))))

(deftest ensure-details!-leaves-existing-file-untouched
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          md-path (str (fs/path dir "1-first-issue.md"))
          before (slurp md-path)]
      (store/ensure-details! (store/find-issue dir 1))
      (is (= before (slurp md-path))))))

(deftest ensure-details!-recreates-missing-linked-file
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (store/write-issue! (assoc (store/find-issue dir 3) :details "nope.md"))
      (store/ensure-details! (store/find-issue dir 3))
      (is (fs/exists? (str (fs/path dir "nope.md")))))))

(deftest read-details-behaviour
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (is (str/includes? (store/read-details (store/find-issue dir 1)) "Some prose."))
      (is (nil? (store/read-details (store/find-issue dir 3)))))))

(deftest dir-problems-duplicate-id
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          dup (assoc (store/find-issue dir 3) :file (str (fs/path dir "3-dup.edn")))]
      (store/write-issue! dup)
      (let [problems (store/dir-problems (store/read-all dir))]
        (is (= 1 (count (filter #(= :duplicate-id (:kind %)) problems))))))))

(deftest dir-problems-dangling-blocked-by
  (let [problems (store/dir-problems [{:id 1 :blocked-by #{99}}])]
    (is (some #(and (= :dangling-blocked-by (:kind %)) (= 99 (:blocker %))) problems))))

(deftest dir-problems-missing-details
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (store/write-issue! (assoc (store/find-issue dir 3) :details "nope.md"))
      (let [problems (store/dir-problems (store/read-all dir))]
        (is (some #(= :missing-details (:kind %)) problems))))))

(deftest dir-problems-clean-fixture
  (test-util/with-temp [tmp "canonical"]
    (is (= [] (store/dir-problems (store/read-all (issues-dir tmp)))))))

(deftest index-markdown-matches-fixture
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          generated (store/index-markdown "canon" (store/read-all dir))]
      (is (= (slurp (str readme-path)) generated))
      (is (= generated (store/index-markdown "canon" (store/read-all dir)))))))

(deftest index-markdown-escapes-pipe
  (let [issue {:id 1 :title "a | b" :status :ready :type :feature :priority :p1
               :file "/tmp/1-x.edn"}]
    (is (str/includes? (store/index-markdown "p" [issue]) "a \\| b"))))

(deftest index-markdown-empty-issues
  (let [md (store/index-markdown "p" [])]
    (is (str/includes? md "## Open\n\n_none_"))
    (is (str/includes? md "## Done\n\n_none_"))
    (is (str/includes? md "## Dropped\n\n_none_"))))

(deftest index-markdown-sorts-by-priority
  (let [low {:id 1 :title "Low" :status :ready :type :feature :priority :p2
             :file "/tmp/1-x.edn"}
        high {:id 2 :title "High" :status :ready :type :feature :priority :p0
              :file "/tmp/2-x.edn"}
        md (store/index-markdown "p" [low high])]
    (is (< (str/index-of md "[2]") (str/index-of md "[1]")))))

(deftest index-state-behaviour
  (test-util/with-temp [tmp "canonical"]
    (is (= :generated (store/index-state (issues-dir tmp)))))
  (test-util/with-temp [tmp nil]
    (is (= :missing (store/index-state tmp))))
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (spit (str (fs/path dir "README.md")) "# hand written\n")
      (is (= :foreign (store/index-state dir))))))

(deftest write-index!-unchanged-fixture
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (is (= {:written? false :state :generated}
             (store/write-index! dir "canon" (store/read-all dir)))))))

(deftest write-index!-status-change-triggers-rewrite
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (store/update! dir 1 #(assoc % :status :done))
      (let [result (store/write-index! dir "canon" (store/read-all dir))
            written (slurp (str (fs/path dir "README.md")))]
        (is (:written? result))
        (is (= :generated (:state result)))
        (is (not (str/includes? written "### Ready")))
        (is (str/includes? written "First issue"))))))

(deftest write-index!-foreign-readme-untouched
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          path (str (fs/path dir "README.md"))]
      (spit path "# hand written\n")
      (let [result (store/write-index! dir "canon" (store/read-all dir))]
        (is (= {:written? false :state :foreign} result))
        (is (= "# hand written\n" (slurp path)))))))

(deftest write-index!-missing-readme-gets-written
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)
          path (str (fs/path dir "README.md"))]
      (fs/delete path)
      (let [result (store/write-index! dir "canon" (store/read-all dir))]
        (is (:written? result))
        (is (fs/exists? path))))))
