(ns issues.ui-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.project :as project]
            [issues.store :as store]
            [issues.test-util :as test-util]
            [issues.ui :as ui]))

(defn- loader
  [& issues-dirs]
  (fn [] (mapv #(project/read-project {:issues-dir %}) issues-dirs)))

(defn- get-page
  [handler uri & [query-string]]
  (handler {:request-method :get :uri uri :query-string query-string}))

(deftest board-and-issue-pages
  (test-util/with-temp [tmp "canonical"]
    (let [dir (str (fs/path tmp ".issues"))
          _ (store/create! dir {:title "Escaped <b>title</b>"} "2026-09-01")
          handler (ui/handler (loader dir))]
      (testing "board"
        (let [{:keys [status headers body]} (get-page handler "/")]
          (is (= 200 status))
          (is (str/starts-with? (get headers "Content-Type") "text/html"))
          (is (str/includes? body "canon issues"))
          (is (str/includes? body "First issue"))
          (is (str/includes? body "blocked by 1"))
          (is (str/includes? body "Third issue"))
          (is (str/includes? body "Escaped &lt;b&gt;title&lt;/b&gt;"))
          (is (not (str/includes? body "<b>title</b>")))
          (is (not (str/includes? body "nav class=\"projects\"")))))
      (testing "issue with details"
        (let [{:keys [status body]} (get-page handler "/i/canon/1")]
          (is (= 200 status))
          (is (str/includes? body "Some prose."))
          (is (str/includes? body "<h1>First issue</h1>"))))
      (testing "issue without details"
        (let [{:keys [status body]} (get-page handler "/i/canon/2")]
          (is (= 200 status))
          (is (str/includes? body "issues details 2"))
          (is (str/includes? body "href=\"/i/canon/1\""))))
      (testing "404s"
        (is (= 404 (:status (get-page handler "/i/canon/42"))))
        (is (= 404 (:status (get-page handler "/i/other/1"))))
        (is (= 404 (:status (get-page handler "/nope"))))))))

(deftest multi-project-board-filters
  (test-util/with-temp [tmp "roots"]
    (let [handler (ui/handler (loader (str (fs/path tmp "alpha" ".issues"))
                                      (str (fs/path tmp "epsilon" ".issues"))))
          all (:body (get-page handler "/"))
          only (:body (get-page handler "/" "project=epsilon"))]
      (is (str/includes? all "alpha#1"))
      (is (str/includes? all "epsilon#1"))
      (is (str/includes? all "nav class=\"projects\""))
      (is (str/includes? only "epsilon#1"))
      (is (not (str/includes? only "alpha#1"))))))

(deftest http-round-trip
  (test-util/with-temp [tmp "canonical"]
    (let [{:keys [port url stop]} (ui/start! {:load (loader (str (fs/path tmp ".issues")))
                                              :port 0})]
      (try
        (is (pos? port))
        (is (str/starts-with? url "http://127.0.0.1:"))
        (is (str/includes? (:body (http/get url)) "First issue"))
        (is (= 404 (:status (http/get (str url "nope") {:throw false}))))
        (finally
          (stop))))))
