(ns kmet.libs.test-scope-self-contained
  "Guard: the store (`kmet.libs.scope`) is stricter than the rest of libs —
   no `kmet.*` require at all, sibling libs included (scope.md §7). Clojure is
   the only library the store uses. Reads the ns form rather than the file
   text: the regex version in the libs guard's history silently checked almost
   nothing when a docstring contained `)`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [kmet.tasks.source-reader :as sr]))

(defn- source-files []
  (->> (fs/glob "modules/libs/src/kmet/libs" "scope*.{clj,cljc}")
       (map str)
       sort))

(defn- ns-form [path]
  (sr/read-ns-form path "scope self-containment guard"))

(defn- kmet-requires
  "Requires of any kmet.* namespace: the store may not compose sibling libs or
   reach into another module."
  [path]
  (when-let [form (ns-form path)]
    (->> (for [clause (rest form)
               :when (and (seq? clause) (= :require (first clause)))
               spec (rest clause)]
           (cond
             (symbol? spec) spec
             (vector? spec) (first spec)
             (seq? spec) (first spec)))
         (map str)
         (filter #(str/starts-with? % "kmet.")))))

(deftest scope-is-self-contained
  (let [files (source-files)]
    (is (seq files) "no scope sources found — the guard is vacuous")
    (doseq [f files]
      (is (empty? (kmet-requires f))
          (str f " must not require kmet.* namespaces, found: " (vec (kmet-requires f)))))))
