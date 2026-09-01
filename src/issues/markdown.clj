(ns issues.markdown
  "A deliberately small markdown-to-hiccup renderer for issue details files:
  headings, paragraphs, bullet and numbered lists, checklists, fenced code,
  and inline code, bold and links. Anything else is plain text. Hiccup
  escapes every string, so untrusted content is safe to render."
  (:require [clojure.string :as str]))

(def ^:private inline-pattern
  #"`([^`]+)`|\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)]+)\)")

(defn inline
  "TEXT split into strings and hiccup for inline code, bold and links."
  [text]
  (let [m (re-matcher inline-pattern text)]
    (loop [pos 0
           out []]
      (if (.find m)
        (let [before (subs text pos (.start m))
              node (cond
                     (.group m 1) [:code (.group m 1)]
                     (.group m 2) [:strong (.group m 2)]
                     :else [:a {:href (.group m 4)} (.group m 3)])]
          (recur (.end m) (cond-> out
                            (seq before) (conj before)
                            true (conj node))))
        (let [tail (subs text pos)]
          (cond-> out (seq tail) (conj tail)))))))

(defn- fence? [line] (boolean (re-matches #"```.*" line)))

(defn- classify
  [line]
  (cond
    (fence? line) :fence
    (str/blank? line) :blank
    (re-find #"^#{1,6} " line) :heading
    (re-find #"^\s*[-*] \[[ xX]\] " line) :task
    (re-find #"^\s*[-*] " line) :bullet
    (re-find #"^\s*\d+[.)] " line) :numbered
    :else :text))

(defn- list-item
  [line]
  (if-let [[_ mark text] (re-matches #"\s*[-*] \[([ xX])\] (.*)" line)]
    (into [:li.task [:input {:type "checkbox" :disabled true :checked (not= " " mark)}] " "]
          (inline text))
    (into [:li] (inline (str/replace line #"^\s*[-*] " "")))))

(defn- numbered-item
  [line]
  (into [:li] (inline (str/replace line #"^\s*\d+[.)] " ""))))

(defn render
  "Markdown TEXT as `[:div.markdown ...]` hiccup."
  [text]
  (loop [lines (str/split-lines (str text))
         out []]
    (if (empty? lines)
      (into [:div.markdown] out)
      (let [line (first lines)]
        (case (classify line)
          :blank (recur (rest lines) out)

          :fence
          (let [[body more] (split-with (complement fence?) (rest lines))]
            (recur (rest more) (conj out [:pre [:code (str/join "\n" body)]])))

          :heading
          (let [[_ hashes title] (re-matches #"(#{1,6}) (.*)" line)]
            (recur (rest lines)
                   (conj out (into [(keyword (str "h" (count hashes)))] (inline title)))))

          (:task :bullet)
          (let [[items more] (split-with #(contains? #{:task :bullet} (classify %)) lines)]
            (recur more (conj out (into [:ul] (map list-item items)))))

          :numbered
          (let [[items more] (split-with #(= :numbered (classify %)) lines)]
            (recur more (conj out (into [:ol] (map numbered-item items)))))

          :text
          (let [[para more] (split-with #(= :text (classify %)) lines)]
            (recur more (conj out (into [:p] (inline (str/join " " (map str/trim para))))))))))))
