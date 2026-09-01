(ns issues.config
  "Loading and normalizing the config that drives project discovery: where
  to crawl, how deep, and which directory names to skip.

  Every path taken or returned by a function here is a plain string, never
  a `java.nio.file.Path`. (`load-config`, not `load`, to avoid shadowing
  `clojure.core/load`.)"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def defaults
  "Config values used when a key is absent from the loaded (or given)
  config."
  {:roots ["~/code/projects"]
   :max-depth 2
   :skip-dirs #{"workspaces" "node_modules" "target" ".git" ".jj"}})

(defn config-path
  "Where the crawler config lives: the `ISSUES_CONFIG` env var when set and
  non-blank, else `<xdg-config-home>/issues/config.edn`."
  []
  (let [env (System/getenv "ISSUES_CONFIG")]
    (if (and env (not (str/blank? env)))
      env
      (str (fs/path (fs/xdg-config-home "issues") "config.edn")))))

(defn data-dir
  "Where project-discovery data is written: `xdg-data-home` for `issues`."
  []
  (str (fs/xdg-data-home "issues")))

(defn normalize
  "Merge CONFIG over `defaults` and coerce the result to canonical shape:
  `:roots` becomes a vector of absolute path strings with `~` expanded,
  `:skip-dirs` becomes a set. `:max-depth` is left as-is."
  [config]
  (-> (merge defaults config)
      (update :roots (fn [roots] (mapv #(str (fs/absolutize (fs/expand-home %))) roots)))
      (update :skip-dirs set)))

(defn load-config
  "Load and normalize the config at PATH (default `(config-path)`). A
  missing file loads as normalized defaults with `:exists? false`. Throws
  `ex-info` with `:issues/exit 1` when the file exists but isn't readable
  EDN, or doesn't read as a map."
  ([] (load-config (config-path)))
  ([path]
   (if-not (fs/exists? path)
     {:path path :exists? false :config (normalize {})}
     (let [unreadable #(ex-info "unreadable config" {:issues/exit 1 :file path})
           v (try (edn/read-string (slurp path)) (catch Exception _ (throw (unreadable))))]
       (if (map? v)
         {:path path :exists? true :config (normalize v)}
         (throw (unreadable)))))))
