(ns kmet.cells.test-self-contained
  "Guard: every kmet.cells.* namespace is self-contained — no kmet.* require
   outside kmet.cells.* itself (cells.md §9). Clojure is the only library the
   store uses. Reads the ns form rather than the file text: the regex version
   in the libs guard's history silently checked almost nothing when a docstring
   contained `)`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]))

(defn- source-files []
  (->> (concat (fs/glob "modules/cells/src" "*.{clj,cljc}")
               (fs/glob "modules/cells/src" "**/*.{clj,cljc}"))
       (map str)
       distinct
       sort))

(defn- ns-form [path]
  (let [form (try (read-string (slurp path))
                  (catch Exception e
                    (throw (ex-info (str "cells self-containment guard cannot read " path)
                                    {} e))))]
    (when (and (seq? form) (= 'ns (first form))) form)))

(defn- kmet-requires
  "Requires of any kmet.* namespace other than kmet.cells.* itself: the
   module may compose its own namespaces, never another module's."
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
         (filter #(str/starts-with? % "kmet."))
         (remove #(str/starts-with? % "kmet.cells.")))))

(deftest cells-is-self-contained
  (let [files (source-files)]
    (is (seq files) "no cells sources found — the guard is vacuous")
    (doseq [f files]
      (is (empty? (kmet-requires f))
          (str f " must not require kmet.* namespaces, found: " (vec (kmet-requires f)))))))
