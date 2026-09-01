(ns issues.render-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [issues.render :as render]))

(def issue
  {:id 2 :ref "canon#2" :project "canon" :title "Second issue" :status :blocked :type :bug
   :priority :p0 :created "2026-08-30" :updated "2026-09-01" :tags #{"b" "a"}
   :blocked-by #{1} :related #{} :file "/tmp/x/2-second-issue.edn"})

(deftest table-pads-columns
  (is (= "ID  NAME\n1   alpha\n22  b\n"
         (render/table ["ID" "NAME"] [[1 "alpha"] [22 "b"]])))
  (is (= "A  B\n" (render/table ["A" "B"] []))))

(deftest json-uses-names-and-arrays
  (let [parsed (json/parse-string (render/->json issue))]
    (is (= "blocked" (get parsed "status")))
    (is (= "p0" (get parsed "priority")))
    (is (= ["a" "b"] (sort (get parsed "tags"))))
    (is (= [1] (get parsed "blocked-by")))))

(deftest edn-round-trips
  (is (= issue (edn/read-string (render/->edn issue)))))

(deftest human-issue-list
  (let [out (render/human {:kind :issue-list :data {:issues [issue] :all? true}})]
    (is (str/starts-with? out "REF"))
    (is (str/includes? out "canon#2"))
    (is (str/includes? out "P0"))
    (is (= "no issues\n" (render/human {:kind :issue-list :data {:issues []}})))))

(deftest human-issue-shows-fields-and-details
  (let [out (render/human {:kind :issue :data (assoc issue :details-text "# Second issue\nbody")})]
    (is (str/includes? out "blocked-by  1"))
    (is (str/includes? out "tags        a, b"))
    (is (str/includes? out "related     -"))
    (is (str/ends-with? out "body"))))

(deftest human-message-and-next
  (is (= "hi\n" (render/human {:kind :message :data "hi"})))
  (is (= "hi\n" (render/human {:kind :message :data "hi\n"})))
  (is (= "nothing ready\n" (render/human {:kind :next :data {:issue nil}}))))

(deftest human-doctor
  (is (= "no problems\n" (render/human {:kind :doctor :data {:problems []}})))
  (let [out (render/human {:kind :doctor
                           :data {:problems [{:kind :bad-status :severity :error :project "p"
                                              :id 3 :msg "must be one of ..."}
                                             {:kind :index-stale :severity :warning
                                              :project "p" :msg "stale"}]}})]
    (is (str/includes? out "error  p#3  bad-status: must be"))
    (is (str/includes? out "1 error(s), 1 warning(s)"))))

(deftest emit-picks-format
  (is (str/starts-with? (render/emit {:kind :message :data "x"} :human) "x"))
  (is (str/starts-with? (render/emit {:kind :message :data {:a 1}} :edn) "{:a 1}"))
  (is (str/starts-with? (render/emit {:kind :message :data {:a 1}} :json) "{")))
