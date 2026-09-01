(ns issues.discover-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [issues.config :as config]
            [issues.discover :as discover]
            [issues.test-util :as test-util]))

(defn- by-id
  [maps]
  (into {} (map (juxt :id identity) maps)))

(deftest discover-finds-default-projects
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp]})
          result (discover/discover cfg)]
      (is (= ["alpha" "epsilon" "omega"] (map :id (:projects result))))
      (is (= (str (fs/path tmp "alpha")) (:path (get (by-id (:projects result)) "alpha"))))
      (is (= (str (fs/path tmp "zomega")) (:path (get (by-id (:projects result)) "omega")))))))

(deftest discover-shadowed-entries
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp]})
          result (discover/discover cfg)]
      (is (= [{:id "omega"
               :path (str (fs/path tmp "a-long-omega-copy"))
               :winner (str (fs/path tmp "zomega"))}
              {:id "alpha"
               :path (str (fs/path tmp "zz-alpha-copy"))
               :winner (str (fs/path tmp "alpha"))}]
             (:shadowed result))))))

(deftest discover-excludes-nested-and-skipped-dirs
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp]})
          result (discover/discover cfg)
          paths (concat (map :path (:projects result)) (map :path (:shadowed result)))]
      (doseq [needle ["workspaces" "node_modules" ".hidden" "delta"]]
        (is (not-any? #(str/includes? % needle) paths) needle)))))

(deftest discover-candidates-require-a-vcs-marker
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp]})]
      (is (= [] (:candidates (discover/discover cfg))))
      (fs/create-dirs (str (fs/path tmp "beta" ".jj")))
      (is (= [{:id "beta" :path (str (fs/path tmp "beta")) :vcs :jj}]
             (:candidates (discover/discover cfg)))))))

(deftest discover-empty-skip-dirs-still-stops-at-project-root
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp] :skip-dirs #{}})
          result (discover/discover cfg)]
      (is (= ["alpha" "epsilon" "evil" "omega"] (map :id (:projects result))))
      (is (not-any? #(str/includes? % "workspaces") (map :path (:projects result)))))))

(deftest discover-max-depth-3-finds-delta
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp] :max-depth 3})
          result (discover/discover cfg)]
      (is (= (str (fs/path tmp "gamma" "deep" "delta"))
             (:path (get (by-id (:projects result)) "delta")))))))

(deftest crawl-stops-descending-at-a-project-root
  (test-util/with-temp [tmp "roots"]
    (let [hits (discover/crawl (str (fs/path tmp "alpha")) {:max-depth 2 :skip-dirs #{}})]
      (is (= 1 (count hits)))
      (is (= :project (:kind (first hits))))
      (is (= 0 (:depth (first hits)))))))

(deftest crawl-records-depth-for-delta-at-max-depth-3
  (test-util/with-temp [tmp "roots"]
    (let [skip-dirs (:skip-dirs (config/normalize {}))
          hits (discover/crawl tmp {:max-depth 3 :skip-dirs skip-dirs})
          delta-hit (first (filter #(str/ends-with? (:path %) "delta") hits))]
      (is (some? delta-hit))
      (is (= 3 (:depth delta-hit))))))

(deftest vcs-root-behaviour
  (test-util/with-temp [tmp nil]
    (let [jj-dir (str (fs/path tmp "jj-dir"))
          git-dir (str (fs/path tmp "git-dir"))
          both-dir (str (fs/path tmp "both-dir"))
          neither-dir (str (fs/path tmp "neither-dir"))]
      (fs/create-dirs (str (fs/path jj-dir ".jj")))
      (is (= :jj (discover/vcs-root jj-dir)))

      (fs/create-dirs git-dir)
      (spit (str (fs/path git-dir ".git")) "gitdir: ../somewhere\n")
      (is (= :git (discover/vcs-root git-dir)))

      (fs/create-dirs (str (fs/path both-dir ".jj")))
      (spit (str (fs/path both-dir ".git")) "gitdir: ../somewhere\n")
      (is (= :jj (discover/vcs-root both-dir)))

      (fs/create-dirs neither-dir)
      (is (nil? (discover/vcs-root neither-dir))))))

(deftest discover-is-deterministic
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp]})]
      (is (= (discover/discover cfg) (discover/discover cfg))))))

(deftest discover-missing-root-warns-and-finds-nothing
  (test-util/with-temp [tmp nil]
    (let [missing (str (fs/path tmp "nope"))
          cfg (config/normalize {:roots [missing]})
          result (discover/discover cfg)]
      (is (= 1 (count (:warnings result))))
      (is (str/includes? (first (:warnings result)) missing))
      (is (= [] (:projects result))))))

(deftest find-project-behaviour
  (test-util/with-temp [tmp "roots"]
    (let [cfg (config/normalize {:roots [tmp]})]
      (is (contains? (discover/find-project cfg "alpha") :issues-dir))
      (let [ex (try (discover/find-project cfg "nope") (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= 1 (:issues/exit (ex-data ex))))
        (is (str/includes? (.getMessage ex) "alpha"))))))

(deftest dedupe-shortest-path-wins
  (let [result (discover/dedupe [{:id "x" :path "/b/long/x"} {:id "x" :path "/a/x"}])]
    (is (= "/a/x" (:path (first (:projects result)))))))
