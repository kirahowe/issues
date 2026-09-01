(ns issues.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hiccup2.core :as h]
            [issues.markdown :as markdown]))

(deftest blocks
  (is (= [:div.markdown] (markdown/render "")))
  (is (= [:div.markdown [:h1 "Title"] [:h2 "Context"]]
         (markdown/render "# Title\n\n## Context\n")))
  (is (= [:div.markdown [:p "one two"] [:p "three"]]
         (markdown/render "one\ntwo\n\nthree")))
  (is (= [:div.markdown [:ul [:li "a"] [:li "b"]] [:ol [:li "x"] [:li "y"]]]
         (markdown/render "- a\n* b\n\n1. x\n2) y")))
  (is (= [:div.markdown [:pre [:code "(+ 1 2)\n# not a heading"]] [:p "after"]]
         (markdown/render "```clojure\n(+ 1 2)\n# not a heading\n```\nafter"))))

(deftest checklists
  (is (= [:div.markdown
          [:ul [:li.task [:input {:type "checkbox" :disabled true :checked false}] " " "todo"]
           [:li.task [:input {:type "checkbox" :disabled true :checked true}] " " "done"]]]
         (markdown/render "- [ ] todo\n- [x] done"))))

(deftest inline-forms
  (is (= ["run " [:code "bb test"] " then " [:strong "commit"] " via " [:a {:href "u"} "link"] "."]
         (markdown/inline "run `bb test` then **commit** via [link](u).")))
  (is (= ["plain"] (markdown/inline "plain")))
  (is (= [:div.markdown [:h2 "Use " [:code "x"]]] (markdown/render "## Use `x`"))))

(deftest escapes-html
  (let [out (str (h/html (markdown/render "a<b & [c](javascript:x)")))]
    (is (str/includes? out "a&lt;b &amp;"))
    (is (not (str/includes? out "<b")))))
