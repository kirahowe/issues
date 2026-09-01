(ns issues.analyze-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.analyze :as analyze]
            [issues.cli :as cli]
            [issues.test-util :as test-util]))

(defn- issue
  [ref title status]
  {:ref ref :project (first (str/split ref #"#")) :title title :status status})

(defn- snap
  [& issues]
  {:scanned-at "2026-09-01T00:00:00Z"
   :projects [{:id "x" :issues (vec issues) :problems []}]
   :shadowed []})

(deftest tokens-and-jaccard
  (is (= #{"add" "age" "backend"} (analyze/tokens "Add the AGE backend!")))
  (is (= #{} (analyze/tokens "a of")))
  (is (= 1.0 (analyze/jaccard #{"a" "b"} #{"a" "b"})))
  (is (= 0.0 (analyze/jaccard #{"a"} #{"b"})))
  (is (= 0.0 (analyze/jaccard #{} #{"b"})))
  (is (= 0.5 (analyze/jaccard #{"a" "b"} #{"a" "b" "c" "d"}))))

(deftest duplicate-titles
  (testing "finds near-duplicate open issues, closed ones excluded by default"
    (let [s (snap (issue "x#1" "Add age backend" :ready)
                  (issue "x#2" "Age backend: add support" :inbox)
                  (issue "x#3" "Add age backend" :done)
                  (issue "x#4" "Unrelated thing entirely" :ready))
          found (analyze/analyze :duplicate-titles s {})]
      (is (= [["x#1" "x#2"]] (map :issues found)))
      (is (= 0.75 (:score (first found))))
      (is (= "shared: add age backend" (:note (first found))))
      (is (= :duplicate-titles (:analyzer (first found))))
      (is (= "2026-09-01T00:00:00Z" (:found-at (first found))))
      (is (= 3 (count (analyze/analyze :duplicate-titles s {:include-closed? true}))))))
  (testing "threshold boundary and sorting"
    (let [s (snap (issue "x#1" "add age" :ready)
                  (issue "x#2" "add age backend now" :ready)
                  (issue "x#3" "add age please" :ready))]
      (is (= [["x#1" "x#3"] ["x#1" "x#2"]]
             (map :issues (analyze/analyze :duplicate-titles s {:threshold 0.5}))))
      (is (= [["x#1" "x#3"]]
             (map :issues (analyze/analyze :duplicate-titles s {:threshold 0.51}))))))
  (testing "single-token titles never pair"
    (is (= [] (analyze/analyze :duplicate-titles
                               (snap (issue "x#1" "Fix" :ready) (issue "x#2" "Fix" :ready))
                               {})))))

(deftest run-all-and-persistence
  (test-util/with-temp [tmp nil]
    (let [path (str (fs/path tmp "nested" "insights.edn"))
          found (analyze/run-all (snap (issue "x#1" "add age backend" :ready)
                                       (issue "x#2" "add age backend" :ready))
                                 {})]
      (is (vector? found))
      (is (= [] (analyze/load-insights path)))
      (analyze/save-insights! path found)
      (is (= found (analyze/load-insights path))))))

(deftest cli-analyze-and-insights
  (test-util/with-temp [tmp "roots"]
    (let [cfg (str (fs/path tmp "config.edn"))
          file (str (fs/path tmp "insights.edn"))
          _ (spit cfg (pr-str {:roots [tmp]}))
          cp (fn [& args] (cli/run (into ["--config" cfg "--edn"] args)))]
      (is (= 1 (:exit (cp "analyze" "--kind" "nope"))))
      (is (= 0 (:exit (cp "analyze" "--no-save" "--file" file))))
      (is (not (fs/exists? file)))
      (let [{:keys [exit data]} (cp "analyze" "--file" file "--threshold" "0.4")]
        (is (= 0 exit))
        (is (vector? data))
        (is (fs/exists? file))
        (is (= data (:data (cp "insights" "--file" file))))
        (is (= [] (:data (cp "insights" "--file" file "--kind" "duplicate-titles"))))))))
