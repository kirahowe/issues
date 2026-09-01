(ns issues.cli-test
  (:require [clojure.test :refer [deftest is]]
            [issues.cli :as cli]))

(deftest help-exits-zero
  (is (= 0 (:exit (cli/run ["help"]))))
  (is (= 0 (:exit (cli/run []))))
  (is (= 0 (:exit (cli/run ["--help"])))))

(deftest unknown-command-exits-one
  (let [{:keys [exit data]} (cli/run ["bogus"])]
    (is (= 1 exit))
    (is (re-find #"unknown command" data))))
