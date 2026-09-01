(ns issues.cli-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.cli :as cli]
            [issues.issue :as issue]
            [issues.render :as render]
            [issues.store :as store]
            [issues.test-util :as test-util]))

(defn- run
  "Run the CLI against the project at TMP, returning the result map."
  [tmp & args]
  (cli/run (into ["--dir" tmp "--edn"] args)))

(defn- issues-dir [tmp] (str (fs/path tmp ".issues")))
(defn- readme [tmp] (slurp (str (fs/path (issues-dir tmp) "README.md"))))

(deftest help-and-unknown
  (is (= 0 (:exit (cli/run ["help"]))))
  (is (= 0 (:exit (cli/run []))))
  (is (= 0 (:exit (cli/run ["--help"]))))
  (let [{:keys [exit data]} (cli/run ["bogus"])]
    (is (= 1 exit))
    (is (re-find #"unknown command" data))))

(deftest format-flags
  (is (= :edn (:format (cli/run ["--edn" "help"]))))
  (is (= :json (:format (cli/run ["--json" "help"]))))
  (is (= :human (:format (cli/run ["help"])))))

(deftest outside-a-project
  (test-util/with-temp [tmp nil]
    (let [{:keys [exit data]} (run tmp "list")]
      (is (= 1 exit))
      (is (str/includes? data "issues init")))))

(deftest init-is-idempotent
  (test-util/with-temp [tmp nil]
    (let [first-run (run tmp "init" "--id" "fresh")]
      (is (= 0 (:exit first-run)))
      (is (str/starts-with? (:data first-run) "initialized"))
      (is (str/starts-with? (readme tmp) store/index-marker))
      (is (= "{:id \"fresh\"}\n" (slurp (str (fs/path (issues-dir tmp) "project.edn")))))
      (is (str/starts-with? (:data (run tmp "init")) "already")))))

(deftest add-files-an-inbox-request
  (test-util/with-temp [tmp "canonical"]
    (let [{:keys [exit data]} (run tmp "add" "New" "thing" "--type" "bug" "-p" "P1" "--tags" "a,b")
          file (str (fs/path (issues-dir tmp) "4-new-thing.edn"))]
      (is (= 0 exit))
      (is (= "canon#4" (:ref data)))
      (is (fs/exists? file))
      (is (not (fs/exists? (str (fs/path (issues-dir tmp) "4-new-thing.md")))))
      (let [on-disk (issue/parse-string (slurp file))]
        (is (= "New thing" (:title on-disk)))
        (is (= :inbox (:status on-disk)))
        (is (= :bug (:type on-disk)))
        (is (= :p1 (:priority on-disk)))
        (is (= #{"a" "b"} (:tags on-disk)))
        (is (= (issue/today) (:created on-disk))))
      (is (str/includes? (readme tmp) "New thing")))
    (testing "defaults and validation"
      (let [{:keys [data]} (run tmp "add" "Plain")]
        (is (= :feature (:type data)))
        (is (= :p2 (:priority data))))
      (is (= 1 (:exit (run tmp "add"))))
      (is (= 1 (:exit (run tmp "add" "x" "--type" "wat")))))))

(deftest list-filters
  (test-util/with-temp [tmp "canonical"]
    (is (= [1 2] (map :id (:issues (:data (run tmp "list"))))))
    (is (= [1 2 3] (map :id (:issues (:data (run tmp "list" "--closed"))))))
    (is (= [3] (map :id (:issues (:data (run tmp "list" "--status" "done"))))))
    (is (= [2] (map :id (:issues (:data (run tmp "list" "--type" "bug"))))))
    (is (= [1] (map :id (:issues (:data (run tmp "list" "--tag" "alpha"))))))
    (is (= [2] (map :id (:issues (:data (run tmp "list" "-p" "p0"))))))
    (is (= 1 (:exit (run tmp "list" "--status" "wip"))))
    (is (false? (:all? (:data (run tmp "list")))))))

(deftest show-and-details
  (test-util/with-temp [tmp "canonical"]
    (let [{:keys [data]} (run tmp "show" "1")]
      (is (= "First issue" (:title data)))
      (is (= "canon#1" (:ref data)))
      (is (str/includes? (:details-text data) "Some prose.")))
    (is (nil? (:details-text (:data (run tmp "show" "2")))))
    (is (= 1 (:exit (run tmp "show" "42"))))
    (let [{:keys [exit data]} (run tmp "details" "2")
          md (str (fs/path (issues-dir tmp) "2-second-issue.md"))]
      (is (= 0 exit))
      (is (= md data))
      (is (str/starts-with? (slurp md) "# Second issue"))
      (is (= "2-second-issue.md" (:details (store/find-issue (issues-dir tmp) 2))))
      (is (str/includes? (readme tmp) "(2-second-issue.md)")))))

(deftest set-validates-and-bumps-updated
  (test-util/with-temp [tmp "canonical"]
    (let [before (slurp (str (fs/path (issues-dir tmp) "1-first-issue.edn")))]
      (is (= 1 (:exit (run tmp "set" "1" "status" "wip"))))
      (is (= before (slurp (str (fs/path (issues-dir tmp) "1-first-issue.edn")))))
      (is (= 1 (:exit (run tmp "set" "1" "bogus" "x"))))
      (is (= 1 (:exit (run tmp "set" "1" "status"))))
      (is (= 1 (:exit (run tmp "set" "x" "status" "done")))))
    (let [{:keys [exit data]} (run tmp "set" "1" "status" "done")]
      (is (= 0 exit))
      (is (= :done (:status data)))
      (is (= (issue/today) (:updated (store/find-issue (issues-dir tmp) 1))))
      (is (str/includes? (readme tmp) "## Done\n\n| ID")))
    (is (= "Renamed" (:title (:data (run tmp "set" "1" "title" "Renamed")))))
    (is (fs/exists? (str (fs/path (issues-dir tmp) "1-first-issue.edn"))))
    (is (= #{"x"} (:tags (:data (run tmp "set" "1" "tags" "x")))))))

(deftest status-sugar
  (test-util/with-temp [tmp "canonical"]
    (is (= :in-progress (:status (:data (run tmp "start" "1")))))
    (is (= :review (:status (:data (run tmp "review" "1")))))
    (is (= :done (:status (:data (run tmp "done" "1")))))
    (is (= :dropped (:status (:data (run tmp "drop" "1")))))))

(deftest block-and-unblock
  (test-util/with-temp [tmp "canonical"]
    (is (= 1 (:exit (run tmp "block" "1" "--on" "1"))))
    (is (= 1 (:exit (run tmp "block" "1" "--on" "99"))))
    (is (= 1 (:exit (run tmp "block" "1"))))
    (let [{:keys [data]} (run tmp "block" "1" "--on" "3")]
      (is (= #{3} (:blocked-by data)))
      (is (= :blocked (:status data))))
    (let [{:keys [data]} (run tmp "unblock" "1" "--on" "3")]
      (is (= #{} (:blocked-by data)))
      (is (= :ready (:status data))))
    (let [{:keys [data]} (run tmp "unblock" "2")]
      (is (= #{} (:blocked-by data)))
      (is (= :ready (:status data))))))

(deftest next-and-inbox
  (test-util/with-temp [tmp "canonical"]
    (is (= 1 (:id (:issue (:data (run tmp "next"))))))
    (run tmp "done" "1")
    (let [{:keys [exit data]} (run tmp "next")]
      (is (= 0 exit))
      (is (nil? (:issue data))))
    (is (= [] (:issues (:data (run tmp "inbox")))))
    (run tmp "add" "Fresh request")
    (is (= ["Fresh request"] (map :title (:issues (:data (run tmp "inbox"))))))))

(deftest index-states
  (test-util/with-temp [tmp "canonical"]
    (is (str/ends-with? (:data (run tmp "index")) "up to date"))
    (store/update! (issues-dir tmp) 3 #(assoc % :status :dropped))
    (is (str/ends-with? (:data (run tmp "index")) "written"))
    (spit (str (fs/path (issues-dir tmp) "README.md")) "# hand written\n")
    (is (= 1 (:exit (run tmp "index"))))
    (testing "mutations still succeed, with a warning, and leave the README alone"
      (let [{:keys [exit warnings]} (run tmp "start" "1")]
        (is (= 0 exit))
        (is (= 1 (count warnings)))
        (is (= "# hand written\n" (readme tmp)))))))

(deftest doctor-exit-codes
  (test-util/with-temp [tmp "canonical"]
    (is (= 0 (:exit (run tmp "doctor"))))
    (fs/copy (str (fs/path (issues-dir tmp) "3-third-issue.edn"))
             (str (fs/path (issues-dir tmp) "3-dup.edn")))
    (let [{:keys [exit data]} (run tmp "doctor")]
      (is (= 2 exit))
      (is (some #(= :duplicate-id (:kind %)) (:problems data)))
      (is (every? #(= "canon" (:project %)) (:problems data))))))

(deftest json-output-parses
  (test-util/with-temp [tmp "canonical"]
    (let [result (cli/run ["--dir" tmp "--json" "show" "2"])
          parsed (json/parse-string (render/emit result (:format result)))]
      (is (= "blocked" (get parsed "status")))
      (is (= [1] (get parsed "blocked-by"))))))

(deftest all-flag-spans-projects
  (test-util/with-temp [tmp "roots"]
    (let [cfg (str (fs/path tmp "config.edn"))
          _ (spit cfg (pr-str {:roots [tmp]}))
          all (fn [& args] (cli/run (into ["--config" cfg "--edn" "--all"] args)))]
      (is (= ["alpha#1" "alpha#2" "epsilon#1"]
             (sort (map :ref (:issues (:data (all "list" "--closed")))))))
      (is (true? (:all? (:data (all "list")))))
      (is (some? (:issue (:data (all "next")))))
      (is (= 0 (:exit (all "inbox"))))
      (let [{:keys [exit data]} (all "doctor")]
        (is (= 0 exit))
        (is (some #(= :shadowed-project (:kind %)) (:problems data))))
      (testing "--project selects one discovered project"
        (let [{:keys [data]} (cli/run ["--config" cfg "--edn" "--project" "alpha" "list"])]
          (is (= ["alpha#1" "alpha#2"] (sort (map :ref (:issues data))))))
        (is (= 1 (:exit (cli/run ["--config" cfg "--edn" "--project" "nope" "list"]))))))))

(deftest edn-output-round-trips
  (test-util/with-temp [tmp "canonical"]
    (let [result (run tmp "show" "1")]
      (is (map? (edn/read-string (render/emit result :edn)))))))

(deftest control-plane-commands
  (test-util/with-temp [tmp "roots"]
    (let [cfg (str (fs/path tmp "config.edn"))
          _ (spit cfg (pr-str {:roots [tmp]}))
          _ (fs/create-dirs (fs/path tmp "beta" ".jj"))
          cp (fn [& args] (cli/run (into ["--config" cfg "--edn"] args)))]
      (testing "projects"
        (let [{:keys [exit data]} (cp "projects")]
          (is (= 0 exit))
          (is (= ["alpha" "epsilon" "omega"] (map :id (:projects data))))
          (is (= 2 (:total (:counts (first (:projects data))))))
          (is (= ["beta"] (map :id (:candidates data))))
          (is (= 2 (count (:shadowed data))))
          (is (str/includes? (render/emit (assoc (cp "projects") :format :human) :human)
                             "candidates"))))
      (testing "status"
        (is (= ["alpha" "epsilon" "omega"] (map :project (:data (cp "status" "--all")))))
        (is (= [2] (map (comp :total :counts) (:data (cp "--project" "alpha" "status"))))))
      (testing "attention spans projects by default and narrows with --project"
        (let [{:keys [data]} (cp "attention")]
          (is (= #{:kira :claude :blocked} (set (keys data))))
          (is (= 3 (count (mapcat val data)))))
        (is (= 2 (count (mapcat val (:data (cp "--project" "alpha" "attention")))))))
      (testing "snapshot"
        (let [{:keys [data] :as result} (cp "snapshot")]
          (is (= #{:scanned-at :roots :projects :candidates :shadowed :warnings}
                 (set (keys data))))
          (is (not-any? #(contains? % :details-text) (mapcat :issues (:projects data))))
          (is (str/starts-with? (render/emit result :human) "{")))
        (is (every? #(contains? % :details-text)
                    (mapcat :issues (:projects (:data (cp "snapshot" "--with-details")))))))
      (testing "config"
        (let [{:keys [data]} (cp "config")]
          (is (= cfg (:path data)))
          (is (true? (:exists? data)))
          (is (= [tmp] (:roots (:config data)))))
        (let [{:keys [data]} (cli/run ["--config" (str (fs/path tmp "nope.edn")) "--edn" "config"])]
          (is (false? (:exists? data))))))))

(deftest status-in-project-scope
  (test-util/with-temp [tmp "canonical"]
    (let [{:keys [data]} (run tmp "status")]
      (is (= [{:project "canon" :total 3 :ready 1 :blocked 1 :done 1}]
             (map #(select-keys (merge (:counts %) {:project (:project %)})
                                [:project :total :ready :blocked :done])
                  data))))))
