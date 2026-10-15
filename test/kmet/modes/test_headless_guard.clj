(ns kmet.modes.test-headless-guard
  "Guard (engine.md §8): a headless front end's static require graph must not
   reach the interactive mode, the TUI view layer, the terminal constructor,
   or the Tk backend. The stricter claim — no `kmet.tui.*` at all — is not
   achievable while extensions are enabled: the extension ABI admits
   `kmet.tui.*` and `host-requires!` pulls the layer in, so `kmet.tui.theme`
   is what remains reachable from both headless modes. This test pins the
   things that must never leak; shrink the allowance when the shared split
   lands."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- ns-file
  "The source file defining namespace N (clj, then cljc, then cljs)."
  [n]
  (let [p (str (str/replace n "." "/") ".clj")]
    (first (filter fs/exists? [(str "src/" p)
                               (str "src/" (str/replace p ".clj" ".cljc"))
                               (str "src/" (str/replace p ".clj" ".cljs"))]))))

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
     :interactive (vec (filter #(str/starts-with? % "kmet.modes.interactive") graph))
     :view (vec (filter #(str/starts-with? % "kmet.app.ui") graph))
     :terminal (vec (filter #(= % "kmet.tui.terminal") graph))
     :tk (vec (filter #(str/starts-with? % "kmet.tk") graph))}))

(deftest headless-front-ends-do-not-reach-the-interactive-stack
  (testing "print and RPC modes require no interactive mode, TUI view layer,
            terminal constructor, or Tk backend"
    (doseq [{:keys [root loaded? interactive view terminal tk]}
            (mapv headless-violations ["kmet.modes.print" "kmet.modes.rpc"])]
      (is loaded? (str root " must be part of the graph"))
      (is (empty? interactive) (str root " reaches " interactive))
      (is (empty? view) (str root " reaches the TUI view layer: " view))
      (is (empty? terminal) (str root " reaches the terminal constructor: " terminal))
      (is (empty? tk) (str root " reaches the Tk backend: " tk)))))
