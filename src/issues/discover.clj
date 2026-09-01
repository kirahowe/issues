(ns issues.discover
  "Crawling configured roots for `.issues/` projects, deduping projects
  whose ids collide, and surfacing version-control roots that could become
  projects.

  Every path taken or returned by a function here is a plain string, never
  a `java.nio.file.Path`."
  (:refer-clojure :exclude [dedupe])
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [issues.project :as project]))

(defn vcs-root
  "The VCS kind DIR is the root of, or nil: `:jj` when DIR/.jj is a
  directory, else `:git` when DIR/.git exists (a directory, or a file, as
  in a git worktree), else nil. `:jj` wins when both are present."
  [dir]
  (cond
    (fs/directory? (fs/path dir ".jj")) :jj
    (fs/exists? (fs/path dir ".git")) :git
    :else nil))

(defn- skip-name?
  [skip-dirs name]
  (or (str/starts-with? name ".") (contains? skip-dirs name)))

(defn- child-dirs
  "DIR's child directories worth descending into, in ascending name order:
  real directories (not symlinks) whose name isn't hidden or in
  SKIP-DIRS. `[]` when DIR can't be listed."
  [dir skip-dirs]
  (try
    (->> (fs/list-dir dir)
         (filter fs/directory?)
         (remove fs/sym-link?)
         (remove #(skip-name? skip-dirs (str (fs/file-name %))))
         (sort-by #(str (fs/file-name %))))
    (catch Exception _ [])))

(defn crawl
  "Depth-first walk from ROOT (depth 0), returning a vector of hits in
  visit order: `{:kind :project :path :issues-dir :depth}` for a directory
  containing `.issues` (never descended into), or `{:kind :vcs-root :path
  :vcs :depth}` for a version-controlled directory (still descended into,
  up to `:max-depth`). A directory that doesn't exist, or can't be listed,
  contributes nothing. OPTS: `:max-depth`, `:skip-dirs`."
  [root {:keys [max-depth skip-dirs]}]
  (letfn [(walk [dir depth]
            (let [dir-str (str (fs/absolutize dir))]
              (cond
                (not (fs/directory? dir-str)) []

                (project/issues-dir? dir-str)
                [{:kind :project :path dir-str
                  :issues-dir (str (fs/path dir-str ".issues")) :depth depth}]

                :else
                (let [vcs (vcs-root dir-str)
                      self-hit (when vcs
                                 [{:kind :vcs-root :path dir-str :vcs vcs :depth depth}])
                      child-hits (when (< depth max-depth)
                                   (mapcat #(walk % (inc depth))
                                           (child-dirs dir-str skip-dirs)))]
                  (vec (concat self-hit child-hits))))))]
    (vec (walk root 0))))

(defn dedupe
  "Group PROJECTS (maps with at least `:id` and `:path`) by `:id`. The
  winner of each group is the entry with the shortest `:path` string (ties
  broken lexicographically). Returns `{:projects <winners, sorted by :id>
  :shadowed [{:id :path :winner} ...]}`, `:shadowed` listing every loser,
  sorted by `:path`."
  [projects]
  (let [rank (juxt (comp count :path) :path)
        by-id (group-by :id projects)
        ids (sort (keys by-id))
        sorted-groups (into {} (for [id ids] [id (sort-by rank (get by-id id))]))
        winners (into {} (for [id ids] [id (first (sorted-groups id))]))]
    {:projects (mapv winners ids)
     :shadowed (->> (for [id ids
                          loser (rest (sorted-groups id))]
                      {:id id :path (:path loser) :winner (:path (winners id))})
                    (sort-by :path)
                    vec)}))

(defn discover
  "Crawl every root in `(:roots config)` and return `{:projects :shadowed
  :candidates :warnings}`. `:projects`/`:shadowed` come from `dedupe` over
  every `:project` hit (id via `project/project-id`); `:candidates`
  (sorted by `:path`) are `{:id :path :vcs}` for every `:vcs-root` hit,
  `:id` being the directory name. `:warnings` names any configured root
  that doesn't exist. Deterministic across runs."
  [config]
  (let [{:keys [roots max-depth skip-dirs]} config
        opts {:max-depth max-depth :skip-dirs skip-dirs}
        per-root (map (fn [root]
                        (if (fs/exists? root)
                          {:hits (crawl root opts)}
                          {:warning (str "root " root " does not exist")}))
                      roots)
        warnings (vec (keep :warning per-root))
        hits (mapcat :hits per-root)
        project-hits (filter #(= :project (:kind %)) hits)
        vcs-hits (filter #(= :vcs-root (:kind %)) hits)
        projects-in (map (fn [{:keys [path issues-dir]}]
                           {:id (project/project-id issues-dir)
                            :path path
                            :issues-dir issues-dir})
                         project-hits)
        {:keys [projects shadowed]} (dedupe projects-in)
        candidates (->> vcs-hits
                        (map (fn [{:keys [path vcs]}]
                               {:id (str (fs/file-name path)) :path path :vcs vcs}))
                        (sort-by :path)
                        vec)]
    {:projects projects :shadowed shadowed :candidates candidates :warnings warnings}))

(defn find-project
  "The project from `(discover config)` whose `:id` is ID. Throws
  `ex-info` with `:issues/exit 1`, listing every known id, when there's no
  such project."
  [config id]
  (let [{:keys [projects]} (discover config)]
    (or (first (filter #(= id (:id %)) projects))
        (throw (ex-info (str "unknown project " id "; known: "
                             (str/join ", " (map :id projects)))
                        {:issues/exit 1})))))
