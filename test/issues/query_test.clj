(ns issues.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [issues.query :as query]))

(defn- issue
  [id status priority & {:as more}]
  (merge {:id id :project "p" :title (str "Issue " id) :status status :type :feature
          :priority priority :tags #{} :blocked-by #{} :related #{}}
         more))

(deftest next-issue-picks-priority-then-id
  (let [issues [(issue 1 :ready :p1) (issue 2 :ready :p0) (issue 3 :ready :p0)]]
    (is (= 2 (:id (query/next-issue issues))))))

(deftest next-issue-skips-open-blockers-only
  (testing "an open blocker hides the issue"
    (is (= 3 (:id (query/next-issue [(issue 1 :in-progress :p2)
                                     (issue 2 :ready :p0 :blocked-by #{1})
                                     (issue 3 :ready :p1)])))))
  (testing "a done blocker does not"
    (is (= 2 (:id (query/next-issue [(issue 1 :done :p2)
                                     (issue 2 :ready :p0 :blocked-by #{1})
                                     (issue 3 :ready :p1)])))))
  (testing "a missing blocker does not"
    (is (= 2 (:id (query/next-issue [(issue 2 :ready :p0 :blocked-by #{99})
                                     (issue 3 :ready :p1)])))))
  (testing "blockers resolve within the issue's own project"
    (is (= 2 (:id (query/next-issue [(issue 1 :in-progress :p0 :project "other")
                                     (issue 2 :ready :p0 :blocked-by #{1})]))))))

(deftest next-issue-nil-when-nothing-ready
  (is (nil? (query/next-issue [(issue 1 :inbox :p0) (issue 2 :done :p0)])))
  (is (nil? (query/next-issue []))))

(deftest sort-orders
  (let [issues [(issue 1 :done :p0) (issue 2 :ready :p2) (issue 3 :inbox :p3)
                (issue 4 :ready :p0) (issue 5 :ready :p2 :project "a")]]
    (is (= [3 4 5 2 1] (map :id (query/sort-issues issues))))
    (is (= [1 4 5 2 3] (map :id (query/by-priority issues))))))

(deftest attention-buckets
  (let [{:keys [kira claude blocked]}
        (query/attention [(issue 1 :review :p1) (issue 2 :inbox :p0) (issue 3 :ready :p2)
                          (issue 4 :blocked :p1) (issue 5 :done :p0)])]
    (is (= [1] (map :id kira)))
    (is (= [2 3] (map :id claude)))
    (is (= [4] (map :id blocked)))))

(deftest counts-cover-every-status
  (let [c (query/counts [(issue 1 :ready :p0) (issue 2 :ready :p0) (issue 3 :done :p0)])]
    (is (= 2 (:ready c)))
    (is (= 1 (:done c)))
    (is (= 0 (:inbox c)))
    (is (= 3 (:total c)))
    (is (= 0 (:total (query/counts []))))))

(deftest matches-and-filter
  (let [i (issue 1 :ready :p1 :tags #{"crypto"} :title "Add AGE backend")]
    (is (query/matches? i {}))
    (is (query/matches? i {:status :ready :tag "crypto" :text "age"}))
    (is (not (query/matches? i {:status :done})))
    (is (not (query/matches? i {:tag "web"})))
    (is (not (query/matches? i {:text "nope"})))
    (is (= [1] (map :id (query/filter-issues [i (issue 2 :ready :p1)] {:tag "crypto"}))))))

(deftest inbox-by-priority
  (is (= [2 1] (map :id (query/inbox [(issue 1 :inbox :p2) (issue 2 :inbox :p0)
                                      (issue 3 :ready :p0)])))))
