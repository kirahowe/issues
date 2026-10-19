(ns issues.criteria
  "The acceptance criteria checklist in an issue's details markdown, and
  whether it is ready to hand to Kira for review.

  Criteria are the top-level `- [ ]` / `- [x]` items under
  `## Acceptance criteria`. A checked item backs up its claim with one or
  more nested receipt lines, the evidence a reviewer can check:

      - [x] `issues tui` lists every issue in review
        - Receipt: `bb test issues.tui-test` passed, 8 tests, at faf954a

  Indented lines that are not receipts (a wrapped item, say) are part of
  the item's text. Every function here is pure."
  (:require [clojure.string :as str]))

(def ^:private item-pattern #"^[-*] \[([ xX])\]\s*(.*)$")
(def ^:private receipt-pattern #"(?i)^\s+[-*]\s+receipts?:\s*(.*)$")

(defn- section
  "The lines under the `## Acceptance criteria` heading of TEXT, up to the
  next heading of level one or two, or nil when TEXT has no such section."
  [text]
  (let [heading? #(re-matches #"(?i)^##\s+acceptance criteria\s*$" %)
        from (drop-while (complement heading?) (str/split-lines (or text "")))]
    (when (seq from)
      (take-while #(not (re-find #"^#{1,2}\s" %)) (rest from)))))

(defn parse
  "The acceptance criteria in TEXT (details markdown) as
  `[{:text s :checked? bool :receipts [s]}]`, or nil when TEXT has no
  acceptance criteria section. Items with no text (the empty `- [ ]` the
  details template starts with) are skipped, as are blank receipts."
  [text]
  (when-let [lines (section text)]
    (->> lines
         ;; IN is where an indented plain line belongs: :text after an item
         ;; line, :receipt after a receipt line, which it continues.
         (reduce (fn [[items in] line]
                   (let [last-at (dec (count items))
                         receipt (second (re-matches receipt-pattern line))]
                     (if-let [[_ mark item-text] (re-matches item-pattern line)]
                       [(conj items {:text (str/trim item-text)
                                     :checked? (not= " " mark)
                                     :receipts []})
                        :text]
                       (cond
                         (or (empty? items) (not (re-find #"^\s+\S" line))) [items nil]
                         receipt [(update-in items [last-at :receipts] conj (str/trim receipt))
                                  :receipt]
                         (= :receipt in)
                         [(update-in items [last-at :receipts]
                                     #(conj (pop %) (str (peek %) " " (str/trim line))))
                          :receipt]
                         (= :text in)
                         [(update-in items [last-at :text] str " " (str/trim line)) :text]
                         :else [items in]))))
                 [[] nil])
         first
         (map (fn [c] (update c :receipts #(filterv (complement str/blank?) (map str/trim %)))))
         (remove #(str/blank? (:text %)))
         vec)))

(defn summary
  "Counts over CRITERIA (from `parse`): `:total`, `:checked`, and
  `:receipted` (checked with at least one receipt)."
  [criteria]
  {:total (count criteria)
   :checked (count (filter :checked? criteria))
   :receipted (count (filter #(and (:checked? %) (seq (:receipts %))) criteria))})

(defn gaps
  "Why the issue whose details are TEXT is not ready for review, as a
  vector of human-readable strings; empty when every criterion is checked
  and has a receipt."
  [text]
  (let [criteria (parse text)]
    (cond
      (nil? text) ["no details file with acceptance criteria"]
      (nil? criteria) ["no ## Acceptance criteria section"]
      (empty? criteria) ["no acceptance criteria listed"]
      :else (vec (concat
                  (for [c criteria :when (not (:checked? c))]
                    (str "unchecked: " (:text c)))
                  (for [c criteria :when (and (:checked? c) (empty? (:receipts c)))]
                    (str "no receipt: " (:text c))))))))
