(ns kmet.agent.modes.test-headless-guard
  "Guard: a headless front end's static require graph must not reach the
   interactive mode, the TUI view layer, or the Tk backend. The stricter
   claim — no `kmet.tui.*` at all — is not achievable while the app config
   resolves the active theme: `kmet.agent.config` reads the shared
   `kmet.tui.theme` store, so that is the one namespace left reachable from
   both headless modes (the extension ABI additionally loads the whole
   shared TUI layer at call time via `host-requires!`, invisible to this
   static graph). That allowance is pinned exactly (the terminal constructor
   and every component are forbidden) and shrinks with the shared split."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- ns-file
  "The source file defining namespace N (clj, then cljc, then cljs)."
  [n]
  (let [p (str (str/replace n "." "/") ".clj")]
    (first (filter fs/exists?
                   (mapcat (fn [root]
                             [(str root "/" p)
                              (str root "/" (str/replace p ".clj" ".cljc"))
                              (str root "/" (str/replace p ".clj" ".cljs"))])
                           ["modules/reakt/src" "modules/hikap/src" "modules/agent/src" "modules/libs/src" "modules/tui/src" "modules/ai/src" "src"])))))

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
  (testing "print and RPC modes require no interactive mode, TUI view layer,
            or Tk backend, and no TUI namespace beyond the kmet.tui.theme
            allowance"
    (doseq [{:keys [root loaded? interactive view tui tk]}
            (mapv headless-violations ["kmet.agent.modes.print" "kmet.agent.modes.rpc"])]
      (is loaded? (str root " must be part of the graph"))
      (is (empty? interactive) (str root " reaches " interactive))
      (is (empty? view) (str root " reaches the TUI view layer: " view))
      (is (= ["kmet.tui.theme"] tui)
          (str root " reaches TUI namespaces beyond the documented allowance: " tui))
      (is (empty? tk) (str root " reaches the Tk backend: " tk)))))

(deftest rpc-client-stays-headless
  (testing "the scripted RPC client loads no view layer at all — not even the
            kmet.tui.theme extension-ABI allowance (it binds no extensions)"
    (let [{:keys [root loaded? interactive view tui tk]}
          (headless-violations "kmet.agent.modes.rpc-client")]
      (is loaded? (str root " must be part of the graph"))
      (is (empty? interactive) (str root " reaches " interactive))
      (is (empty? view) (str root " reaches the TUI view layer: " view))
      (is (empty? tui) (str root " reaches the TUI layer: " tui))
      (is (empty? tk) (str root " reaches the Tk backend: " tk)))))
