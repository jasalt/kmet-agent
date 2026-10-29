(ns kmet.test-process-exit-guard
  "Guard: a process ends at its entry point, not inside a library.

   `kmet.app.core/main` returns the exit code and `-main` is the only form
   that calls `System/exit`; the mode rows report a status, and an extension
   gets a degraded capability rather than the process. The same rule keeps
   the TUI testable (a test cannot survive an exit) and keeps teardown
   reachable. The two model generators are `-main` functions shipped as
   developer tools, so they are the named exceptions.

   A test would not catch this by failing — it would kill the runner — which
   is why the rule is a source scan."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(def ^:private entry-exits
  "The files whose `System/exit` is an entry point's (or a developer tool's
   own `-main`)."
  #{"modules/app/src/kmet/app/core.clj"
    "modules/ai/src/kmet/ai/model_gen.clj"
    "modules/ai/src/kmet/ai/image_model_gen.clj"})

(defn- src-files
  "Every Clojure source file under a module's src tree, repo-relative and
   `/`-normalized."
  []
  (->> (fs/glob "modules" "**/src/**/*.{clj,cljc}")
       (filter fs/regular-file?)
       (map #(str/replace (str %) "\\" "/"))
       sort))

(defn- exit-sites
  "The `file:line` sites in FILE where `System/exit` appears."
  [file]
  (->> (str/split-lines (slurp file))
       (map-indexed (fn [i line] (when (str/includes? line "System/exit")
                                   [file (inc i)])))
       (remove nil?)))

(deftest system-exit-belongs-to-the-entry
  (let [files (src-files)
        sites (mapcat exit-sites files)
        owners (set (map first sites))]
    (testing "the scan sees the module sources"
      (is (seq files))
      (is (contains? (set files) "modules/app/src/kmet/app/core.clj")))
    (testing "every exit site is an allowed entry point"
      (doseq [[file line] sites]
        (is (contains? entry-exits file)
            (str file ":" line " calls System/exit — an entry point owns the"
                 " process; return a status, throw, or stop the front end")))
      (doseq [file entry-exits]
        (is (contains? owners file)
            (str file " is on the allowlist but no longer calls System/exit;"
                 " shrink the list"))))))
