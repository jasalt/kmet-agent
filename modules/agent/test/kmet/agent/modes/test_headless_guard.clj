(ns kmet.agent.modes.test-headless-guard
  "Guard: a headless front end's static require graph reaches no
   interactive mode, no `kmet.tui.*` namespace, and no Tk backend. The
   extension ABI and the config command load the shared TUI layer at call
   time (`host-requires!`, `kmet.agent.modes.config-command`), invisible to
   this static graph."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tasks.modules :as modules]))

(defn- ns-file
  "The source file defining namespace N (clj, then cljc, then cljs)."
  [n]
  (let [p (str (str/replace n "." "/") ".clj")]
    (first (filter fs/exists?
                   (mapcat (fn [root]
                             [(str root "/" p)
                              (str root "/" (str/replace p ".clj" ".cljc"))
                              (str root "/" (str/replace p ".clj" ".cljs"))])
                           (conj (modules/module-src-paths) "src"))))))

(defn- required-namespaces
  "The kmet.* namespaces PATH's ns form requires. Reads the ns form itself —
   reader conditionals resolve to this host's branch, which is exactly the
   view the running host loads."
  [path]
  (let [form (try (read-string (slurp path))
                  (catch Exception e
                    (throw (ex-info (str "headless guard cannot read " path) {} e))))
        ns-form (when (and (seq? form) (= 'ns (first form))) form)]
    (when ns-form
      (->> (for [clause (rest ns-form)
                 :when (and (seq? clause) (#{:require :require-macros} (first clause)))
                 spec (rest clause)
                 :when (or (symbol? spec) (vector? spec) (seq? spec))]
             (str (if (symbol? spec) spec (first spec))))
           (filter #(str/starts-with? % "kmet."))
           distinct))))

(defn- transitive-requires
  "Every kmet.* namespace reachable from ROOTS through static ns requires."
  [roots]
  (loop [seen #{} queue (vec roots)]
    (if-let [n (first queue)]
      (if (contains? seen n)
        (recur seen (vec (rest queue)))
        (let [deps (if-let [f (ns-file n)] (required-namespaces f) [])]
          (recur (conj seen n) (into (vec (rest queue)) (remove seen deps)))))
      seen)))

(defn- headless-violations [root]
  (let [graph (transitive-requires [root])]
    {:root root
     :loaded? (contains? graph root)
     :interactive (vec (filter #(str/starts-with? % "kmet.agent.modes.interactive") graph))
     :view (vec (filter #(str/starts-with? % "kmet.agent.ui") graph))
     :tui (vec (filter #(str/starts-with? % "kmet.tui") graph))
     :tk (vec (filter #(str/starts-with? % "kmet.tk") graph))}))

(deftest headless-front-ends-do-not-reach-the-interactive-stack
  (testing "print and RPC modes require no interactive mode, TUI
            namespace, or Tk backend"
    (doseq [{:keys [root loaded? interactive view tui tk]}
            (mapv headless-violations ["kmet.agent.modes.print" "kmet.agent.modes.rpc"])]
      (is loaded? (str root " must be part of the graph"))
      (is (empty? interactive) (str root " reaches " interactive))
      (is (empty? view) (str root " reaches the TUI view layer: " view))
      (is (empty? tui) (str root " reaches the TUI layer: " tui))
      (is (empty? tk) (str root " reaches the Tk backend: " tk)))))

(deftest rpc-client-stays-headless
  (testing "the scripted RPC client loads no view layer at all (it binds
            no extensions)"
    (let [{:keys [root loaded? interactive view tui tk]}
          (headless-violations "kmet.agent.modes.rpc-client")]
      (is loaded? (str root " must be part of the graph"))
      (is (empty? interactive) (str root " reaches " interactive))
      (is (empty? view) (str root " reaches the TUI view layer: " view))
      (is (empty? tui) (str root " reaches the TUI layer: " tui))
      (is (empty? tk) (str root " reaches the Tk backend: " tk)))))
