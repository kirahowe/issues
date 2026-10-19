(ns issues.tui
  "A terminal inbox of the issues awaiting Kira's review, where each one can
  be approved, sent back with changes requested, or rejected.

  The interface is a pure state machine: `step` takes a state and a key and
  returns `[state effect]`, and `render` turns a state into screen lines.
  `run-inbox!` does the IO: it puts the terminal in raw mode with `stty`,
  reads keys, carries out effects through `issues.review`, and redraws
  with plain ANSI escapes, since babashka bundles no curses library."
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [issues.issue :as issue]
            [issues.query :as query]
            [issues.review :as review]
            [issues.store :as store]))

;;;; State

(defn init-state
  "The starting state over ISSUES (already in review order)."
  [issues]
  {:issues (vec issues) :sel 0 :view :list :scroll 0 :prompt nil :flash nil})

(defn selected
  "The issue under the cursor, or nil when the inbox is empty."
  [{:keys [issues sel]}]
  (get issues sel))

(defn with-issues
  "STATE showing a fresh ISSUES list, keeping the cursor on the same issue
  when it is still there. When it has gone (just reviewed, say), the cursor
  stays at the same position, so the detail view moves on to the next
  issue; an empty list falls back to the list view."
  [state issues]
  (let [issues (vec issues)
        ref (:ref (selected state))
        same (first (keep-indexed (fn [i x] (when (= ref (:ref x)) i)) issues))
        sel (or same (max 0 (min (:sel state) (dec (count issues)))))]
    (cond-> (assoc state :issues issues :sel sel)
      (not same) (assoc :scroll 0)
      (empty? issues) (assoc :view :list))))

;;;; Keys

(def ^:private verdict-keys
  {"a" :approve "c" :request-changes "r" :reject})

(defn- move [state delta]
  (let [n (count (:issues state))]
    (if (zero? n)
      state
      (assoc state :sel (max 0 (min (dec n) (+ (:sel state) delta))) :scroll 0))))

(defn- scroll [state delta]
  (assoc state :scroll (max 0 (+ (:scroll state) delta))))

(defn- open-prompt [state verdict]
  (if (selected state)
    (assoc state :prompt {:verdict verdict :text ""} :flash nil)
    state))

(defn- step-prompt
  [state key]
  (let [{:keys [verdict text]} (:prompt state)]
    (cond
      (#{:esc :ctrl-c} key) [(assoc state :prompt nil :flash "cancelled") nil]
      (= :enter key)
      (if (and (get-in review/verdicts [verdict :note?]) (str/blank? text))
        [(assoc state :flash "a note is required to request changes") nil]
        [(assoc state :prompt nil) [:verdict (selected state) verdict text]])
      (= :backspace key)
      [(update-in state [:prompt :text] #(subs % 0 (max 0 (dec (count %))))) nil]
      (= :ctrl-u key) [(assoc-in state [:prompt :text] "") nil]
      (and (string? key) (not (neg? (compare key " "))))
      [(assoc state :prompt {:verdict verdict :text (str text key)} :flash nil) nil]
      :else [state nil])))

(defn step
  "Apply KEY to STATE. KEY is a one-character string or one of `:up`
  `:down` `:pgup` `:pgdn` `:enter` `:esc` `:backspace` `:ctrl-c` `:ctrl-u`.
  Returns `[state effect]`, where effect is nil or one of
  `[:verdict issue verdict note]`, `[:edit issue]`, `[:reload]`, `[:quit]`."
  [state key]
  (if (:prompt state)
    (step-prompt state key)
    (let [state (assoc state :flash nil)
          page 10]
      (if-let [verdict (get verdict-keys key)]
        [(open-prompt state verdict) nil]
        (case [(:view state) key]
          ([:list "j"] [:list :down]) [(move state 1) nil]
          ([:list "k"] [:list :up]) [(move state -1) nil]
          ([:list :enter] [:list "l"])
          [(cond-> state (selected state) (assoc :view :detail :scroll 0)) nil]
          ([:list "q"] [:list :esc]) [state [:quit]]
          ([:detail "j"] [:detail :down]) [(scroll state 1) nil]
          ([:detail "k"] [:detail :up]) [(scroll state -1) nil]
          ([:detail " "] [:detail :pgdn]) [(scroll state page) nil]
          ([:detail "b"] [:detail :pgup]) [(scroll state (- page)) nil]
          ([:detail "n"]) [(move state 1) nil]
          ([:detail "p"]) [(move state -1) nil]
          ([:detail "q"] [:detail :esc] [:detail "h"]) [(assoc state :view :list) nil]
          (case key
            :ctrl-c [state [:quit]]
            "e" [state (when-let [i (selected state)] [:edit i])]
            "R" [state [:reload]]
            [state nil]))))))

;;;; Rendering

(def ^:private esc "\u001b[")
(defn- bold [s] (str esc "1m" s esc "0m"))
(defn- dim [s] (str esc "2m" s esc "0m"))
(defn- inverse [s] (str esc "7m" s esc "0m"))

(defn- fit
  "S cut or right-padded to exactly W characters."
  [s w]
  (let [s (str s)]
    (if (> (count s) w)
      (str (subs s 0 (max 0 (dec w))) (when (pos? w) "…"))
      (str s (apply str (repeat (- w (count s)) " "))))))

(defn wrap
  "TEXT broken into lines of at most W characters, at spaces where it can."
  [text w]
  (let [w (max 10 w)]
    (mapcat (fn [line]
              (loop [line line out []]
                (if (<= (count line) w)
                  (conj out line)
                  (let [cut (or (some-> (str/last-index-of line " " w) (#(when (pos? %) %))) w)]
                    (recur (str/triml (subs line cut)) (conj out (subs line 0 cut)))))))
            (str/split-lines (or text "")))))

(defn- pri [i] (some-> (:priority i) name str/upper-case))

(defn- list-lines
  [{:keys [issues sel]} w h]
  (if (empty? issues)
    [(dim " Nothing awaiting review.")]
    (let [ref-w (apply max (map (comp count :ref) issues))
          top (max 0 (min (- sel (quot h 2)) (- (count issues) h)))]
      (for [[i x] (map-indexed vector issues)
            :when (<= top i (+ top h -1))
            :let [line (fit (str " " (fit (:ref x) ref-w) "  " (fit (pri x) 2) "  "
                                 (fit (name (:type x)) 7) "  " (:updated x) "  " (:title x))
                            w)]]
        (if (= i sel) (inverse line) line)))))

(defn detail-body
  "The scrollable lines of ISSUE's detail view at width W."
  [x w]
  (let [meta (fn [label v] (str " " (fit label 10) " " v))
        set-str (fn [s] (if (seq s) (str/join ", " (sort-by str s)) "-"))]
    (concat
     (map #(str " " %) (wrap (:title x) (- w 2)))
     [""
      (meta "status" (name (:status x)))
      (meta "type" (str (name (:type x)) "  " (pri x)))
      (meta "updated" (:updated x))
      (meta "tags" (set-str (:tags x)))
      (meta "related" (set-str (:related x)))
      ""]
     (if-let [text (:details-text x)]
       (map #(str " " %) (wrap text (- w 2)))
       [(dim " (no details file)")]))))

(defn- help-line
  [{:keys [view issues]}]
  (cond
    (empty? issues) "R reload · q quit"
    (= :list view) "j/k move  ⏎ read  a approve  c changes  r reject  e edit  R reload  q quit"
    :else "j/k ␣/b scroll  n/p next/prev  a approve  c changes  r reject  e edit  q back"))

(defn- prompt-line
  [{:keys [prompt] :as state} cols]
  (let [{:keys [verdict text]} prompt
        ref (:ref (selected state))
        label (case verdict
                :approve (str "Approve " ref "? Note (optional): ")
                :request-changes (str "Changes for " ref ": ")
                :reject (str "Reject " ref "? Reason (optional): "))
        room (max 1 (- cols (count label) 2))
        shown (if (> (count text) room)
                (str "…" (subs text (- (count text) (dec room))))
                text)]
    (str " " (bold label) shown (inverse " "))))

(defn render
  "STATE as exactly ROWS lines for a COLS-wide terminal."
  [state rows cols]
  (let [body-h (max 1 (- rows 3))
        n (count (:issues state))
        x (selected state)
        header (if (= :detail (:view state))
                 (str " " (:ref x) "  ·  " (inc (:sel state)) " of " n)
                 (str " Review inbox  ·  " n " awaiting review"))
        body (if (and x (= :detail (:view state)))
               (let [lines (vec (detail-body x cols))
                     top (min (:scroll state) (max 0 (- (count lines) body-h)))]
                 (subvec lines top (min (count lines) (+ top body-h))))
               (list-lines state cols body-h))
        status (cond
                 (:prompt state) (prompt-line state cols)
                 (:flash state) (fit (str " " (:flash state)) cols)
                 :else "")]
    (vec (concat [(bold (fit header cols))]
                 body
                 (repeat (- body-h (count body)) "")
                 [status (dim (fit (str " " (help-line state)) cols))]))))

;;;; Effects

(defn load-reviews
  "The review inbox from LOAD-ISSUES (a no-arg fn returning every issue in
  scope), by priority, each with its details text."
  [load-issues]
  (->> (load-issues)
       (filter #(= :review (:status %)))
       query/by-priority
       (mapv #(assoc % :details-text (store/read-details %)))))

(defn apply-effect!
  "Carry out a `:verdict` EFFECT and return the flash message for it."
  [[_ x verdict note]]
  (try
    (let [updated (review/apply-verdict! x verdict note (issue/today))]
      (str (get-in review/verdicts [verdict :label]) " " (:ref updated)
           " → " (name (:status updated))))
    (catch clojure.lang.ExceptionInfo e
      (str "error: " (ex-message e)))))

;;;; Terminal

(defn- tty! [& args]
  (-> (process/sh "sh" "-c" (str "stty " (str/join " " args) " < /dev/tty"))
      :out str/trim))

(defn- size []
  (let [[r c] (map parse-long (str/split (tty! "size") #"\s+"))]
    [(or r 24) (or c 80)]))

(defn- out! [& s]
  (print (apply str s))
  (flush))

(defn- read-key
  "The next key from READER, as `step` expects it, or :eof."
  [^java.io.Reader reader]
  (let [c (.read reader)
        more? (fn [] (Thread/sleep 15) (.ready reader))]
    (case c
      -1 :eof
      27 (if-not (more?)
           :esc
           (let [c2 (.read reader)]
             (if-not (#{(int \[) (int \O)} c2)
               :esc
               (case (char (.read reader))
                 \A :up \B :down \C "l" \D "h"
                 \5 (do (.read reader) :pgup)
                 \6 (do (.read reader) :pgdn)
                 nil))))
      (10 13) :enter
      (8 127) :backspace
      3 :ctrl-c
      21 :ctrl-u
      (str (char c)))))

(defn- enter-screen! []
  (tty! "-icanon" "-echo" "-isig" "-ixon" "min" "1")
  (out! esc "?1049h" esc "?25l"))

(defn- leave-screen! [saved]
  (out! esc "?25h" esc "?1049l")
  (tty! saved))

(defn- draw! [state]
  (let [[rows cols] (size)]
    (out! esc "H" (str/join "\r\n" (map #(str % esc "K") (render state rows cols))) esc "J")))

(defn run-inbox!
  "Run the review inbox over LOAD-ISSUES until the user quits. EDIT! is
  called with an issue to open its details in an editor, with the
  terminal restored for the duration. Returns the message for every
  verdict given, in order."
  [load-issues edit!]
  (let [saved (tty! "-g")
        reader (java.io.InputStreamReader. System/in "UTF-8")
        restore (Thread. #(leave-screen! saved))]
    (.addShutdownHook (Runtime/getRuntime) restore)
    (enter-screen!)
    (try
      (loop [state (init-state (load-reviews load-issues))
             given []]
        (draw! state)
        (let [key (read-key reader)
              [state effect] (if (= :eof key) [state [:quit]] (step state key))
              reload #(with-issues % (load-reviews load-issues))]
          (case (first effect)
            :quit given
            :reload (recur (assoc (reload state) :flash "reloaded") given)
            :verdict (let [msg (apply-effect! effect)]
                       (recur (assoc (reload state) :flash msg) (conj given msg)))
            :edit (do (leave-screen! saved)
                      (edit! (second effect))
                      (enter-screen!)
                      (recur (reload state) given))
            (recur state given))))
      (finally
        (leave-screen! saved)
        (.removeShutdownHook (Runtime/getRuntime) restore)))))
