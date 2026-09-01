(ns issues.issue-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.issue :as issue]
            [issues.test-util :as test-util]))

(def ^:private issues-dir
  (fs/path test-util/fixtures-dir "canonical" ".issues"))

(defn- issue-edn-files
  "The fixture `.edn` files that represent issues, i.e. every `*.edn` file
  under `issues-dir` except `project.edn`."
  []
  (->> (fs/glob issues-dir "*.edn")
       (remove #(str/ends-with? (str %) "project.edn"))
       sort))

(def valid-issue
  {:id 1
   :title "A valid issue"
   :status :ready
   :type :feature
   :priority :p1
   :created "2026-09-01"
   :updated "2026-09-01"
   :tags #{"a"}
   :blocked-by #{}
   :related #{}})

(deftest round-trip-fixtures
  (doseq [f (issue-edn-files)]
    (let [content (slurp (str f))]
      (is (= content (issue/render-string (issue/parse-string content))) (str f)))))

(deftest round-trip-scrambled-order-is-a-fixed-point
  (let [m {:updated "2026-09-01"
           :status :ready
           :id 5
           :owner "kira"
           :title "Scrambled"
           :type :feature
           :priority :p2
           :created "2026-09-01"
           :tags #{}
           :blocked-by #{}
           :related #{}}
        rendered (issue/render-string m)
        parsed (issue/parse-string rendered)]
    (is (= m parsed))
    (is (= rendered (issue/render-string parsed)))))

(deftest sets-render-sorted
  (is (re-find #"#\{2 9 10\}" (issue/render-string {:blocked-by #{10 9 2}})))
  (is (re-find #"#\{\"a\" \"b\"\}" (issue/render-string {:tags #{"b" "a"}}))))

(deftest normalize-converts-string-enums-to-keywords
  (is (= :ready (:status (issue/normalize {:status "Ready"}))))
  (is (= :feature (:type (issue/normalize {:type "FEATURE"}))))
  (is (= :p1 (:priority (issue/normalize {:priority "p1"})))))

(deftest normalize-coerces-collections-to-sets
  (is (= #{} (:tags (issue/normalize {:tags nil}))))
  (is (= #{"a" "b"} (:tags (issue/normalize {:tags ["a" "b"]}))))
  (is (= #{1 2} (:blocked-by (issue/normalize {:blocked-by (list 1 2)})))))

(deftest normalize-does-not-add-missing-keys
  (is (= #{:id} (set (keys (issue/normalize {:id 1}))))))

(deftest parse-string-unreadable-input
  (doseq [s ["{:id 1" "42"]]
    (let [result (issue/parse-string s)]
      (is (= [:problems] (keys result)) s)
      (is (= 1 (count (:problems result))) s)
      (is (= :unreadable (:kind (first (:problems result)))) s))))

(deftest runtime-keys-never-render
  (let [issue (assoc valid-issue :file "/x" :problems [])
        out (issue/render-string issue)]
    (is (not (str/includes? out "file")))
    (is (not (str/includes? out "problems")))))

(deftest validate-a-valid-issue
  (is (= [] (issue/validate valid-issue))))

(deftest validate-missing-field
  (is (= [:missing-field] (map :kind (issue/validate (dissoc valid-issue :title))))))

(deftest validate-bad-scalar-fields
  (testing "bad-id"
    (is (= [:bad-id] (map :kind (issue/validate (assoc valid-issue :id -1))))))
  (testing "bad-title"
    (is (= [:bad-title] (map :kind (issue/validate (assoc valid-issue :title "   "))))))
  (testing "bad-status"
    (is (= [:bad-status] (map :kind (issue/validate (assoc valid-issue :status :nope))))))
  (testing "bad-type"
    (is (= [:bad-type] (map :kind (issue/validate (assoc valid-issue :type :nope))))))
  (testing "bad-priority"
    (is (= [:bad-priority] (map :kind (issue/validate (assoc valid-issue :priority :p9))))))
  (testing "bad-date, including an invalid calendar date"
    (is (= [:bad-date]
           (map :kind (issue/validate (assoc valid-issue :created "2026-13-40")))))))

(deftest validate-bad-collection-fields
  (testing "bad-tags"
    (is (= [:bad-tags] (map :kind (issue/validate (assoc valid-issue :tags #{"ok" 5}))))))
  (testing "bad-blocked-by"
    (is (= [:bad-blocked-by]
           (map :kind (issue/validate (assoc valid-issue :blocked-by #{-1}))))))
  (testing "self-block"
    (is (= [:self-block] (map :kind (issue/validate (assoc valid-issue :blocked-by #{1}))))))
  (testing "bad-related"
    (is (= [:bad-related] (map :kind (issue/validate (assoc valid-issue :related #{"bad"}))))))
  (testing "bad-details"
    (is (= [:bad-details] (map :kind (issue/validate (assoc valid-issue :details "  ")))))))

(deftest coerce-field-behaviour
  (testing "status"
    (is (contains? (issue/coerce-field :status "WIP") :error))
    (is (= {:value :ready} (issue/coerce-field :status "Ready"))))
  (testing "priority"
    (is (= {:value :p1} (issue/coerce-field :priority "P1"))))
  (testing "tags"
    (is (= #{"a" "b" "c"} (:value (issue/coerce-field :tags "a, b,,c")))))
  (testing "blocked-by"
    (is (contains? (issue/coerce-field :blocked-by "1, x") :error))
    (is (= #{3 4} (:value (issue/coerce-field :blocked-by "3,4")))))
  (testing "related"
    (is (contains? (issue/coerce-field :related "foo#1,bad") :error)))
  (testing "title"
    (is (contains? (issue/coerce-field :title "  ") :error)))
  (testing "unknown field"
    (is (= {:error "unknown field"} (issue/coerce-field :nope "x")))))

(deftest assoc-field-bumps-updated
  (let [issue (issue/assoc-field valid-issue :status :done "2026-09-05")]
    (is (= :done (:status issue)))
    (is (= "2026-09-05" (:updated issue)))))

(deftest new-issue-defaults-and-shape
  (let [i (issue/new-issue {:id 7 :title "New" :today "2026-09-01"})]
    (is (= 7 (:id i)))
    (is (= "New" (:title i)))
    (is (= :inbox (:status i)))
    (is (= :feature (:type i)))
    (is (= :p2 (:priority i)))
    (is (= "2026-09-01" (:created i)))
    (is (= "2026-09-01" (:updated i)))
    (is (= #{} (:tags i)))
    (is (= #{} (:blocked-by i)))
    (is (= #{} (:related i)))
    (is (not (contains? i :details)))))

(deftest ref-string-and-parse-ref-round-trip
  (is (= "proj#12" (issue/ref-string "proj" 12)))
  (is (= {:project "proj" :id 12} (issue/parse-ref "proj#12")))
  (is (nil? (issue/parse-ref "nope"))))

(deftest details-template-shape
  (let [t (issue/details-template {:title "My Issue"})]
    (is (str/starts-with? t "# My Issue"))
    (is (str/includes? t "## Context"))
    (is (str/includes? t "## Acceptance criteria"))
    (is (str/includes? t "## Plan"))
    (is (str/includes? t "## Log"))))
