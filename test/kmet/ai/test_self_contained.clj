(ns kmet.ai.test-self-contained
  "Guard: every kmet.ai.* namespace must be self-contained — no requires on
   app, tui, modes, or sibling ai namespaces beyond kmet.libs.*. This is the
   structural boundary of the provider/auth subsystem (pi: packages/ai is a
   standalone library the agent depends on; the agent never feeds back into
   it)."
  (:require [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- ai-files []
  (letfn [(walk [dir]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (walk "ai/src/kmet/ai")
         (filter #(re-find #"\.clj[ca]?$" (str %)))
         (map str))))

(defn- ns-form [path]
  ;; Read the ns form itself — a regex over the file stops at the first `)`
  ;; inside the ns docstring and silently checks nothing.
  (let [form (try (read-string (slurp path))
                  (catch Exception e
                    (throw (ex-info (str "self-containment guard cannot read " path) {} e))))]
    (when (and (seq? form) (= 'ns (first form))) form)))

(defn- foreign-kmet-requires [path]
  (when-let [form (ns-form path)]
    (->> (for [clause (rest form)
               :when (and (seq? clause) (= :require (first clause)))
               spec (rest clause)]
           (cond
             (symbol? spec) spec
             (vector? spec) (first spec)
             (seq? spec) (first spec)))
         (map str)
         (filter #(str/starts-with? % "kmet."))
         (remove #(or (= % "kmet.ai")
                      (str/starts-with? % "kmet.ai.")
                      (str/starts-with? % "kmet.libs."))))))

(deftest ai-is-self-contained
  (doseq [f (ai-files)]
    (let [deps (foreign-kmet-requires f)]
      (is (empty? deps)
          (str f " must only require kmet.libs.*, found: " deps)))))