(ns issues.project-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [issues.project :as project]
            [issues.store :as store]
            [issues.test-util :as test-util]))

(defn- issues-dir
  [tmp]
  (str (fs/path tmp ".issues")))

(deftest locate-from-nested-dir-in-project
  (test-util/with-temp [tmp "canonical"]
    (let [root (str (fs/absolutize tmp))
          sub (str (fs/path root "sub" "dir"))]
      (fs/create-dirs sub)
      (is (= {:root root :issues-dir (str (fs/path root ".issues"))}
             (project/locate sub))))))

(deftest locate-returns-nil-with-no-ancestor-issues-dir
  (test-util/with-temp [tmp nil]
    (is (nil? (project/locate tmp)))))

(deftest project-id-canonical
  (test-util/with-temp [tmp "canonical"]
    (is (= "canon" (project/project-id (issues-dir tmp))))))

(deftest project-id-falls-back-to-parent-dir-name
  (test-util/with-temp [tmp nil]
    (let [dir (str (fs/path tmp "mything" ".issues"))]
      (fs/create-dirs dir)
      (is (= "mything" (project/project-id dir))))))

(deftest read-project-edn-unreadable
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (spit (str (fs/path dir "project.edn")) "{")
      (let [ex (try (project/read-project-edn dir) (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= 1 (:issues/exit (ex-data ex))))))))

(deftest init!-creates-issues-dir-once
  (test-util/with-temp [tmp nil]
    (let [dir (issues-dir tmp)
          result (project/init! tmp {})]
      (is (true? (:created? result)))
      (is (fs/directory? dir))
      (is (str/starts-with?
           (slurp (str (fs/path dir "README.md")))
           store/index-marker))
      (let [result2 (project/init! tmp {})]
        (is (false? (:created? result2)))))))

(deftest init!-writes-project-edn-with-given-id
  (test-util/with-temp [tmp nil]
    (project/init! tmp {:id "x"})
    (is (= "{:id \"x\"}\n" (slurp (str (fs/path (issues-dir tmp) "project.edn")))))))

(deftest read-project-canonical
  (test-util/with-temp [tmp "canonical"]
    (let [result (project/read-project {:issues-dir (issues-dir tmp)})]
      (is (= "canon" (:id result)))
      (is (= (str (fs/absolutize tmp)) (:path result)))
      (is (= 3 (count (:issues result))))
      (is (every? #(= "canon" (:project %)) (:issues result)))
      (is (= ["canon#1" "canon#2" "canon#3"] (map :ref (:issues result))))
      (is (= [] (:problems result))))))

(deftest read-project-index-stale-after-status-change
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (store/update! dir 1 #(assoc % :status :done))
      (let [result (project/read-project {:issues-dir dir})]
        (is (some #(= :index-stale (:kind %)) (:problems result)))))))

(deftest read-project-index-missing-when-readme-deleted
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (fs/delete (str (fs/path dir "README.md")))
      (let [result (project/read-project {:issues-dir dir})]
        (is (some #(= :index-missing (:kind %)) (:problems result)))))))

(deftest read-project-index-foreign-when-readme-hand-written
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (spit (str (fs/path dir "README.md")) "# hand written\n")
      (let [result (project/read-project {:issues-dir dir})]
        (is (some #(= :index-foreign (:kind %)) (:problems result)))))))

(deftest read-project-unreadable-issue-is-annotated
  (test-util/with-temp [tmp "canonical"]
    (let [dir (issues-dir tmp)]
      (spit (str (fs/path dir "9-broken.edn")) "{")
      (let [result (project/read-project {:issues-dir dir})]
        (is (some #(and (= :unreadable (:kind %)) (= 9 (:id %))) (:problems result)))))))
