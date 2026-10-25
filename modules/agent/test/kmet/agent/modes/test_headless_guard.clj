(ns kmet.agent.modes.test-headless-guard
  "Guard: a headless front end's static require graph reaches no UI layer
   (`kmet.ui.*`, the TUI and its interactive cluster included), no
   `kmet.tui.*` widget namespace, and no Tk backend. The extension ABI and the
   config command load the shared TUI layer at call time (`host-requires!`,
   `kmet.ui.tui.config-command`), invisible to this static graph."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tasks.modules :as modules]
            [kmet.tasks.source-reader :as sr]))

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
  (let [ns-form (sr/read-ns-form path "headless guard")]
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
     :ui (vec (filter #(str/starts-with? % "kmet.ui") graph))
     :tui (vec (filter #(str/starts-with? % "kmet.tui") graph))
     :tk (vec (filter #(str/starts-with? % "kmet.tk") graph))}))

(defn- assert-headless
  "ROOT's static require graph reaches no UI layer, widget namespace or Tk
   backend, and ROOT itself was loaded (an empty graph would pass vacuously)."
  [root]
  (let [{:keys [loaded? ui tui tk]} (headless-violations root)]
    (is loaded? (str root " must be part of the graph"))
    (is (empty? ui) (str root " reaches the UI layer: " ui))
    (is (empty? tui) (str root " reaches the TUI layer: " tui))
    (is (empty? tk) (str root " reaches the Tk backend: " tk))))

(deftest headless-front-ends-do-not-reach-the-ui-layer
  (testing "print and RPC modes require no UI layer, TUI widget namespace, or
            Tk backend"
    (doseq [root ["kmet.agent.modes.print" "kmet.agent.modes.rpc"]]
      (assert-headless root))))

(deftest rpc-client-stays-headless
  (testing "the scripted RPC client loads no view layer at all (it binds
            no extensions)"
    (assert-headless "kmet.agent.modes.rpc-client")))
