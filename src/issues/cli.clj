(ns issues.cli
  "Command-line entry point for the issues tracker.

  `run` takes the argument vector and returns a result map; `-main` prints it
  and exits. Keeping `System/exit` out of `run` lets tests drive the CLI
  in-process."
  (:require [clojure.string :as str]))

(def usage
  (str/join "\n"
            ["issues - per-project issue tracker and cross-project control plane"
             ""
             "Usage: issues <command> [args]"
             ""
             "Commands:"
             "  help    Show this message"
             ""]))

(defn run
  "Run the CLI with ARGS (a sequence of strings).

  Returns {:exit int :kind keyword :data any :warnings [string]}. Never calls
  System/exit."
  [args]
  (let [[cmd] args]
    (if (or (nil? cmd) (contains? #{"help" "-h" "--help"} cmd))
      {:exit 0 :kind :message :data usage}
      {:exit 1 :kind :message :data (str "issues: unknown command " (pr-str cmd) "\n\n" usage)})))

(defn -main [& args]
  (let [{:keys [exit data warnings]} (run args)]
    (doseq [w warnings]
      (binding [*out* *err*] (println "warning:" w)))
    (if (zero? exit)
      (print data)
      (binding [*out* *err*] (print data)))
    (flush)
    (System/exit exit)))
