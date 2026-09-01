(ns issues.snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [issues.config :as config]
            [issues.snapshot :as snapshot]
            [issues.store :as store]
            [issues.test-util :as test-util]))

(defn- snap [tmp opts]
  (snapshot/build (config/normalize {:roots [tmp]}) opts))

(deftest build-shape
  (test-util/with-temp [tmp "roots"]
    (let [s (snap tmp {})]
      (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z" (:scanned-at s)))
      (is (= [tmp] (:roots s)))
      (is (= ["alpha" "epsilon" "omega"] (map :id (:projects s))))
      (is (every? #(contains? % :problems) (:projects s)))
      (is (= ["alpha#1" "alpha#2" "epsilon#1"] (map :ref (snapshot/all-issues s))))
      (is (= "alpha" (:project (get (snapshot/index-by-ref s) "alpha#1"))))
      (is (= 2 (count (:shadowed s))))
      (is (= [] (:warnings s))))))

(deftest details-inlined-on-request
  (test-util/with-temp [tmp "roots"]
    (testing "absent by default"
      (is (not-any? #(contains? % :details-text) (snapshot/all-issues (snap tmp {})))))
    (testing "present when asked, nil when there is no details file"
      (let [issues (snapshot/all-issues (snap tmp {:with-details? true}))]
        (is (every? #(contains? % :details-text) issues))
        (is (nil? (:details-text (first issues))))))))

(deftest cross-problems-find-dangling-related-and-shadows
  (test-util/with-temp [tmp "roots"]
    (store/update! (str tmp "/alpha/.issues") 1
                   #(assoc % :related #{"epsilon#1" "nope#9"}))
    (let [problems (snapshot/cross-problems (snap tmp {}))
          dangling (filter #(= :dangling-related (:kind %)) problems)]
      (is (= [{:ref "alpha#1" :related "nope#9"}]
             (map #(select-keys % [:ref :related]) dangling)))
      (is (= #{"alpha" "omega"}
             (set (map :id (filter #(= :shadowed-project (:kind %)) problems))))))))

(deftest all-problems-tag-project
  (test-util/with-temp [tmp "roots"]
    (spit (str tmp "/alpha/.issues/7-broken.edn") "{")
    (let [problems (snapshot/all-problems (snap tmp {}))
          unreadable (first (filter #(= :unreadable (:kind %)) problems))]
      (is (= "alpha" (:project unreadable)))
      (is (= 7 (:id unreadable))))))
