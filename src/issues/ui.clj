(ns issues.ui
  "A read-only local web view over one or more projects' issues: a board
  of open issues by status, and one page per issue with its details file
  rendered. Every request re-reads `.issues/` so CLI edits show up on
  refresh. Served by http-kit on 127.0.0.1 only."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [issues.markdown :as markdown]
            [issues.query :as query]
            [issues.store :as store]
            [org.httpkit.server :as server]))

(def css
  (str/join
   "\n"
   [":root{--bg:#f6f7f9;--fg:#1c1e21;--card:#fff;--muted:#6b7280;--line:#e5e7eb;--accent:#2563eb}"
    "@media(prefers-color-scheme:dark){:root{--bg:#111318;--fg:#e6e8eb;--card:#1b1e25;"
    "--muted:#9aa0a6;--line:#2a2e37;--accent:#7aa2f7}}"
    "*{box-sizing:border-box}"
    "body{margin:0;background:var(--bg);color:var(--fg);"
    "font:14px/1.45 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
    "header{padding:14px 24px;border-bottom:1px solid var(--line);display:flex;gap:18px;"
    "align-items:baseline;flex-wrap:wrap}"
    "h1{margin:0;font-size:18px}"
    "nav.projects a{margin-right:14px;color:var(--muted);text-decoration:none}"
    "nav.projects a.on{color:var(--fg);font-weight:600}"
    ".count{color:var(--muted);font-weight:400;margin-left:6px;font-size:12px}"
    "main.board{display:grid;grid-template-columns:repeat(5,minmax(210px,1fr));gap:12px;"
    "padding:16px 24px;overflow-x:auto}"
    ".column h2{font-size:12px;text-transform:uppercase;letter-spacing:.04em;"
    "color:var(--muted);margin:0 0 8px}"
    ".card{display:block;background:var(--card);border:1px solid var(--line);"
    "border-left-width:4px;border-radius:6px;padding:8px 10px;margin-bottom:8px;"
    "color:inherit;text-decoration:none}"
    ".card:hover{border-color:var(--accent)}"
    ".card-head{display:flex;gap:8px;font-size:12px;color:var(--muted)}"
    ".card-title{margin-top:4px}"
    ".pri-p0{border-left-color:#dc2626}.pri-p1{border-left-color:#f59e0b}"
    ".pri-p2{border-left-color:#3b82f6}.pri-p3{border-left-color:#9ca3af}"
    ".tags{margin-top:6px}"
    ".tag{font-size:11px;background:var(--bg);border:1px solid var(--line);"
    "border-radius:10px;padding:1px 7px;margin-right:4px}"
    ".blocked{margin-top:6px;font-size:12px;color:#dc2626}"
    "details.closed{padding:8px 24px 24px}"
    "details.closed summary{cursor:pointer;color:var(--muted)}"
    "details.closed li{margin:4px 0}"
    "a{color:var(--accent)}"
    ".muted{color:var(--muted)}"
    "main.issue{max-width:820px;padding:16px 24px}"
    "dl.meta{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin:0 0 20px}"
    "dt{color:var(--muted)}dd{margin:0}"
    ".markdown pre{background:var(--card);border:1px solid var(--line);padding:10px;"
    "border-radius:6px;overflow-x:auto}"
    ".markdown code{background:var(--card);border:1px solid var(--line);"
    "border-radius:4px;padding:0 4px}"
    ".markdown li.task{list-style:none;margin-left:-20px}"
    "a.back{color:var(--muted);text-decoration:none}"]))

(def columns
  "Board columns, in lifecycle order."
  [:inbox :ready :in-progress :review :blocked])

(defn- label [status] (str/capitalize (str/replace (name status) "-" " ")))
(defn- pri [issue] (some-> (:priority issue) name str/upper-case))
(defn- issue-href [issue] (str "/i/" (:project issue) "/" (:id issue)))
(defn- issue-label [issue multi?] (if multi? (:ref issue) (str "#" (:id issue))))

(defn page
  "A complete HTML document. OPTS: `:refresh?` reloads every 10 seconds."
  [title {:keys [refresh?]} & body]
  (str "<!doctype html>"
       (h/html
        [:html
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          (when refresh? [:meta {:http-equiv "refresh" :content "10"}])
          [:title title]
          [:style (h/raw css)]]
         (into [:body] body)])))

(defn card
  [issue multi?]
  [:a.card {:href (issue-href issue) :class (str "pri-" (name (:priority issue :p2)))}
   [:div.card-head
    [:span.id (issue-label issue multi?)]
    [:span.pri (pri issue)]
    [:span.type (name (:type issue :feature))]]
   [:div.card-title (:title issue)]
   (when (seq (:tags issue))
     [:div.tags (for [t (sort (:tags issue))] [:span.tag t])])
   (when (seq (:blocked-by issue))
     [:div.blocked "blocked by " (str/join ", " (sort (:blocked-by issue)))])])

(defn- project-nav
  [projects filter-id]
  [:nav.projects
   [:a {:href "/" :class (when-not filter-id "on")} "all"]
   (for [p projects]
     [:a {:href (str "/?project=" (:id p)) :class (when (= filter-id (:id p)) "on")}
      (:id p)
      [:span.count (count (filter query/open? (:issues p)))]])])

(defn board-page
  "The board over PROJECTS (each `{:id :issues}`), optionally narrowed to
  FILTER-ID when there are several."
  [projects filter-id]
  (let [multi? (> (count projects) 1)
        shown (if filter-id (filter #(= filter-id (:id %)) projects) projects)
        issues (query/by-priority (mapcat :issues shown))
        by-status (group-by :status issues)
        closed (concat (by-status :done) (by-status :dropped))
        title (if multi? "issues" (str (:id (first projects)) " issues"))]
    (page title {:refresh? true}
          [:header
           [:h1 title]
           (when multi? (project-nav projects filter-id))]
          [:main.board
           (for [status columns]
             [:section.column
              [:h2 (label status) [:span.count (count (by-status status))]]
              (for [issue (by-status status)] (card issue multi?))])]
          [:details.closed
           [:summary "Done " (count (by-status :done))
            " · Dropped " (count (by-status :dropped))]
           [:ul (for [issue closed]
                  [:li [:a {:href (issue-href issue)} (issue-label issue multi?)]
                   " " (:title issue) [:span.muted " " (name (:status issue))]])]])))

(defn- set-cell [s] (if (seq s) (str/join ", " (sort-by str s)) "-"))

(defn issue-page
  [issue multi?]
  (let [href (fn [id] (str "/i/" (:project issue) "/" id))]
    (page (str (issue-label issue multi?) " " (:title issue)) {}
          [:header
           [:a.back {:href "/"} "← board"]
           [:h1 [:span.muted (issue-label issue multi?)] " " (:title issue)]]
          [:main.issue
           [:dl.meta
            [:dt "status"] [:dd (name (:status issue))]
            [:dt "type"] [:dd (name (:type issue))]
            [:dt "priority"] [:dd (pri issue)]
            [:dt "created"] [:dd (:created issue)]
            [:dt "updated"] [:dd (:updated issue)]
            [:dt "tags"] [:dd (set-cell (:tags issue))]
            [:dt "blocked by"] [:dd (if (seq (:blocked-by issue))
                                      (interpose ", " (for [b (sort (:blocked-by issue))]
                                                        [:a {:href (href b)} (str "#" b)]))
                                      "-")]
            [:dt "related"] [:dd (set-cell (:related issue))]
            [:dt "file"] [:dd [:code (:file issue)]]]
           (if-let [text (store/read-details issue)]
             (markdown/render text)
             [:p.muted "No details file yet. Run "
              [:code (str "issues details " (:id issue))] " to create one."])])))

(defn- parse-query
  [query-string]
  (into {}
        (for [pair (str/split (str query-string) #"&")
              :when (seq pair)
              :let [[k v] (str/split pair #"=" 2)]]
          [k (java.net.URLDecoder/decode (str v) "UTF-8")])))

(defn- html-response
  [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- find-issue
  [projects project-id id]
  (some (fn [p]
          (when (= project-id (:id p))
            (first (filter #(= id (:id %)) (:issues p)))))
        projects))

(defn handler
  "A Ring handler over LOAD, a function returning the current vector of
  projects (each `{:id :issues}`). Routes: `/` (board, `?project=` filter)
  and `/i/<project>/<id>`; anything else is 404."
  [load]
  (fn [{:keys [uri query-string]}]
    (let [projects (load)
          multi? (> (count projects) 1)]
      (if-let [[_ project-id id] (re-matches #"/i/([^/]+)/(\d+)" uri)]
        (if-let [issue (find-issue projects project-id (parse-long id))]
          (html-response 200 (issue-page issue multi?))
          (html-response 404 (page "not found" {} [:p "no such issue"])))
        (if (= "/" uri)
          (html-response 200 (board-page projects (get (parse-query query-string) "project")))
          (html-response 404 (page "not found" {} [:p "no such page"])))))))

(defn start!
  "Serve `(handler load)` on 127.0.0.1:PORT (0 picks a free port). Returns
  `{:port :url :stop}`."
  [{:keys [load port]}]
  (let [s (server/run-server (handler load)
                             {:ip "127.0.0.1" :port port :legacy-return-value? false})
        actual (server/server-port s)]
    {:port actual
     :url (str "http://127.0.0.1:" actual "/")
     :stop (fn [] (server/server-stop! s))}))

(defn open-browser!
  "Open URL in the default browser; silently does nothing when no opener
  is available."
  [url]
  (when-let [opener (some #(when (fs/which %) %) ["open" "xdg-open"])]
    (try
      (process/shell {:out :discard :err :discard} opener url)
      (catch Exception _ nil))))
