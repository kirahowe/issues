(ns issues.criteria-test
  (:require [clojure.test :refer [deftest is testing]]
            [issues.criteria :as criteria]
            [issues.issue :as issue]))

(def details
  (str "# T\n\n## Context\n\n- [x] not a criterion\n\n"
       "## Acceptance criteria\n\n"
       "- [x] Lists every issue in review across the\n"
       "      configured roots\n"
       "  - Receipt: `bb test issues.tui-test` passed,\n"
       "    6 tests\n"
       "  - receipt: driven through a pty\n"
       "- [X] Logs the note\n"
       "  - Receipt:   \n"
       "- [ ] Opens the editor\n"
       "\n### Stretch\n\n- [ ] Multi-line notes\n"
       "\n## Plan\n\n- [ ] not a criterion either\n"))

(deftest parse
  (is (= [{:text "Lists every issue in review across the configured roots" :checked? true
           :receipts ["`bb test issues.tui-test` passed, 6 tests" "driven through a pty"]}
          {:text "Logs the note" :checked? true :receipts []}
          {:text "Opens the editor" :checked? false :receipts []}
          {:text "Multi-line notes" :checked? false :receipts []}]
         (criteria/parse details)))
  (testing "the details template's empty item is skipped"
    (is (= [] (criteria/parse (issue/details-template {:title "T"})))))
  (testing "no section"
    (is (nil? (criteria/parse "# T\n\nprose\n")))
    (is (nil? (criteria/parse nil))))
  (testing "a heading on the last line"
    (is (= [] (criteria/parse "# T\n\n## Acceptance criteria")))))

(deftest summary
  (is (= {:total 4 :checked 2 :receipted 1} (criteria/summary (criteria/parse details))))
  (is (= {:total 0 :checked 0 :receipted 0} (criteria/summary nil))))

(deftest gaps
  (is (= ["unchecked: Opens the editor" "unchecked: Multi-line notes"
          "no receipt: Logs the note"]
         (criteria/gaps details)))
  (is (= ["no details file with acceptance criteria"] (criteria/gaps nil)))
  (is (= ["no ## Acceptance criteria section"] (criteria/gaps "# T\n")))
  (is (= ["no acceptance criteria listed"] (criteria/gaps (issue/details-template {:title "T"}))))
  (is (= [] (criteria/gaps "## Acceptance criteria\n\n- [x] Done\n  - Receipt: ok\n"))))
