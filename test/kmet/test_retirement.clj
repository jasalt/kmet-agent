(ns kmet.test-retirement
  "Guard: the retired namespace prefix. `modules/agent` is kmet1, and landing
   11 deletes it; every other module's sources speak their own module's name.

   `kmet.extension` is the ABI and `kmet.agent.ui.tui.tool-renderers` is the one
   UI namespace shipped extensions require by that name — neither is a leftover,
   so both are named here rather than pattern-matched. The require graph has no
   edge from this file to what it scans, so it runs in the full suite."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(def ^:private kept
  "Names starting with the retired prefix that are contracts, not leftovers."
  #{"kmet.agent.ui.tui.tool-renderers"})

(defn- source-files
  "Every Clojure source file under the module src trees other than `agent`'s."
  []
  (->> (fs/glob "modules" "*/src")
       (filter #(not= "agent" (str (fs/file-name (fs/parent %)))))
       (mapcat (fn [dir] (fs/glob dir "**.{clj,cljc,cljd}")))))

(defn- declared-ns
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader (str path)))]
    (let [form (read {:eof nil :read-cond :allow} r)]
      (when (and (seq? form) (= 'ns (first form)))
        (str (second form))))))

(t/deftest only-kmet1-speaks-kmet1s-name
  (let [stale (->> (source-files)
                   (map (fn [f] [(str f) (declared-ns f)]))
                   (filter (fn [[_ ns]] (and ns (str/starts-with? ns "kmet.agent.")
                                             (not (contains? kept ns)))))
                   (sort))]
    (t/is (seq (source-files)) "no sources scanned — check the walk roots")
    (t/is (every? (fn [f] (some? (declared-ns f))) (source-files))
          "every scanned file declares a namespace — a reader that returned nil
           for all of them would make this guard vacuous")
    (t/is (empty? stale)
          (str "namespaces outside `agent` still use the retired prefix: "
               (mapv second stale)))))
