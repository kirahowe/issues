(ns issues.tui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [issues.tui :as tui]))

(def issues
  [{:ref "a#1" :id 1 :project "a" :title "One" :status :review :type :feature
    :priority :p1 :updated "2026-10-01" :details-text "## Log\n\n- did it"}
   {:ref "b#4" :id 4 :project "b" :title "Four" :status :review :type :bug
    :priority :p2 :updated "2026-10-02"}])

(defn- keys->
  "Feed KEYS through `step` from STATE, returning the last [state effect]."
  [state & keys]
  (reduce (fn [[s _] k] (tui/step s k)) [state nil] keys))

(def start (tui/init-state issues))

(deftest navigation
  (is (= 1 (:sel (first (keys-> start "j")))))
  (is (= 1 (:sel (first (keys-> start :down :down :down)))))
  (is (= 0 (:sel (first (keys-> start "j" "k")))))
  (is (= :detail (:view (first (keys-> start :enter)))))
  (is (= :list (:view (first (keys-> start :enter :esc)))))
  (is (= 1 (:sel (first (keys-> start :enter "n")))))
  (is (= [:quit] (second (keys-> start "q"))))
  (is (= [:quit] (second (keys-> start :ctrl-c))))
  (is (= [:reload] (second (keys-> start "R"))))
  (is (= [:edit (first issues)] (second (keys-> start "e"))))
  (testing "escape in the detail view goes back rather than quitting"
    (is (nil? (second (keys-> start :enter :esc))))))

(deftest verdict-prompts
  (testing "approve with no note"
    (is (= [:verdict (first issues) :approve ""] (second (keys-> start "a" :enter)))))
  (testing "typing, backspace, and keys that are commands elsewhere"
    (is (= [:verdict (second issues) :reject "no q"]
           (second (keys-> start "j" "r" "n" "o" " " "q" "x" :backspace :enter)))))
  (testing "request changes refuses an empty note"
    (let [[s effect] (keys-> start "c" " " :enter)]
      (is (nil? effect))
      (is (:prompt s))
      (is (re-find #"note is required" (:flash s)))))
  (testing "escape cancels"
    (let [[s effect] (keys-> start "c" "x" :esc)]
      (is (nil? effect))
      (is (nil? (:prompt s)))))
  (testing "nothing to judge in an empty inbox"
    (is (nil? (:prompt (first (keys-> (tui/init-state []) "a")))))))

(deftest refreshing-the-list
  (let [reading (first (keys-> start :enter))]
    (testing "the cursor follows the same issue"
      (let [s (tui/with-issues (first (keys-> start "j")) (reverse issues))]
        (is (= 0 (:sel s)))
        (is (= "b#4" (:ref (tui/selected s))))))
    (testing "a reviewed issue gone from the detail view moves on to the next"
      (let [s (tui/with-issues reading (rest issues))]
        (is (= :detail (:view s)))
        (is (= "b#4" (:ref (tui/selected s))))))
    (testing "an emptied inbox returns to the list"
      (is (= :list (:view (tui/with-issues reading [])))))))

(deftest rendering
  (let [plain #(str/replace % #"\u001b\[[0-9;?]*[A-Za-z]" "")]
    (testing "every frame fills the screen exactly"
      (doseq [s [start (first (keys-> start :enter)) (first (keys-> start "c" "h" "i"))
                 (tui/init-state [])]]
        (is (= 12 (count (tui/render s 12 60))))))
    (testing "list"
      (let [lines (map plain (tui/render start 12 60))]
        (is (str/includes? (first lines) "2 awaiting review"))
        (is (some #(str/includes? % "a#1  P1  feature  -      2026-10-01  One") lines))
        (is (every? #(<= (count %) 60) lines))))
    (testing "detail"
      (let [lines (map plain (tui/render (first (keys-> start :enter)) 20 60))]
        (is (str/includes? (first lines) "a#1  ·  1 of 2"))
        (is (some #(str/includes? % "- did it") lines))))
    (testing "prompt"
      (let [lines (map plain (tui/render (first (keys-> start "c" "h" "i")) 12 60))]
        (is (some #(str/includes? % "Changes for a#1: hi") lines))))
    (testing "empty"
      (is (some #(str/includes? % "Nothing awaiting review")
                (map plain (tui/render (tui/init-state []) 12 60)))))))

(deftest criteria-cell
  (let [cell #(tui/criteria-cell {:details-text (str "## Acceptance criteria\n\n" %)})]
    (is (= "-" (tui/criteria-cell {})))
    (is (= "1/1" (cell "- [x] a\n  - Receipt: ok\n")))
    (is (= "1/2!" (cell "- [x] a\n  - Receipt: ok\n- [x] b\n")))
    (is (= "0/1!" (cell "- [ ] a\n")))))

(deftest wrap
  (is (= ["aaa bbb" "ccc"] (tui/wrap "aaa bbb ccc" 10)))
  (is (= ["" "x"] (tui/wrap "\nx" 10))))
