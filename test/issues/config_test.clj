(ns issues.config-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [issues.config :as config]
            [issues.test-util :as test-util]))

(deftest normalize-of-empty-matches-normalized-defaults
  (is (= (config/normalize config/defaults) (config/normalize {}))))

(deftest normalize-roots-are-absolute-with-no-tilde
  (let [n (config/normalize {})]
    (is (seq (:roots n)))
    (is (every? #(not (str/includes? % "~")) (:roots n)))
    (is (every? #(str/starts-with? % "/") (:roots n)))))

(deftest normalize-skip-dirs-is-a-set-containing-workspaces
  (let [n (config/normalize {})]
    (is (set? (:skip-dirs n)))
    (is (contains? (:skip-dirs n) "workspaces"))))

(deftest load-config-missing-file-is-normalized-defaults
  (test-util/with-temp [tmp nil]
    (let [path (str (fs/path tmp "nope.edn"))
          result (config/load-config path)]
      (is (false? (:exists? result)))
      (is (= (config/normalize {}) (:config result))))))

(deftest load-config-reads-and-normalizes-a-file
  (test-util/with-temp [tmp nil]
    (let [path (str (fs/path tmp "config.edn"))]
      (spit path (pr-str {:roots ["~/x" "/abs/y"] :skip-dirs ["a"]}))
      (let [result (config/load-config path)]
        (is (true? (:exists? result)))
        (is (= [(str (fs/absolutize (fs/expand-home "~/x"))) "/abs/y"]
               (:roots (:config result))))
        (is (= #{"a"} (:skip-dirs (:config result))))
        (is (= 2 (:max-depth (:config result))))))))

(deftest load-config-unreadable-file-throws
  (test-util/with-temp [tmp nil]
    (let [path (str (fs/path tmp "bad.edn"))]
      (spit path "{")
      (let [ex (try (config/load-config path) (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= 1 (:issues/exit (ex-data ex))))))))

(deftest load-config-non-map-file-throws
  (test-util/with-temp [tmp nil]
    (let [path (str (fs/path tmp "num.edn"))]
      (spit path "42")
      (let [ex (try (config/load-config path) (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (= 1 (:issues/exit (ex-data ex))))))))

(deftest config-path-and-data-dir-shape
  (is (str/ends-with? (config/config-path) "issues/config.edn"))
  (is (str/ends-with? (config/data-dir) "issues")))
