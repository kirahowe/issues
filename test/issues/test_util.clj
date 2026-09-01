(ns issues.test-util
  "Helpers shared by the test namespaces: fixture copies in temp dirs."
  (:require [babashka.fs :as fs]))

(def fixtures-dir
  "Fixtures live under test/fixtures; tests run from the repo root."
  (fs/path "test" "fixtures"))

(defn temp-dir
  "Create a fresh temp dir and return its path as a string."
  []
  (str (fs/create-temp-dir {:prefix "issues-test-"})))

(defn copy-fixture
  "Copy test/fixtures/NAME into a fresh temp dir and return that dir's path."
  [name]
  (let [tmp (temp-dir)]
    (fs/copy-tree (fs/path fixtures-dir name) tmp)
    tmp))

(defmacro with-temp
  "Bind SYM to a fresh temp dir (or a fixture copy when NAME is given), run
  BODY, and delete the dir afterwards."
  [[sym name] & body]
  `(let [~sym ~(if name `(copy-fixture ~name) `(temp-dir))]
     (try
       ~@body
       (finally
         (fs/delete-tree ~sym)))))
