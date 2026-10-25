(ns kmet.test-retirement
  "Guard: the retired namespace prefix. `modules/agent` is kmet1, and landing
   11 deletes it; every other module's sources speak their own module's name.

   `kmet.extension` is the ABI and `kmet.agent.ui.tui.tool-renderers` is the one
   UI namespace shipped extensions require by that name — neither is a leftover,
   so both are named here rather than pattern-matched. The module list is
   `modules.edn`'s, so a module added later is scanned without an edit here. The
   require graph has no edge from this file to what it scans, so it runs in the
   full suite."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.tasks.modules :as modules]))

(def ^:private kept
  "Names starting with the retired prefix that are contracts, not leftovers."
  #{"kmet.agent.ui.tui.tool-renderers"})

(defn- source-files
  "Every Clojure source file of every module but kmet1's."
  []
  (->> (remove #(= "agent" (:name %)) (modules/modules))
       (mapcat (fn [{:keys [src]}] (fs/glob src "**.{clj,cljc,cljd}")))
       (map str)
       sort))

(defn- declared-ns
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (let [form (read {:eof nil :read-cond :allow} r)]
      (when (and (seq? form) (= 'ns (first form)))
        (str (second form))))))

(t/deftest only-kmet1-speaks-kmet1s-name
  (let [files (source-files)
        parsed (map (fn [f] [f (declared-ns f)]) files)
        stale (->> parsed
                   (filter (fn [[_ ns]] (and ns (str/starts-with? ns "kmet.agent.")
                                             (not (contains? kept ns)))))
                   sort)]
    (t/is (seq files) "no sources scanned — check the module list")
    (t/is (every? (comp some? second) parsed)
          "every scanned file declares a namespace — a reader that returned nil
           for all of them would make this guard vacuous")
    (t/is (empty? stale)
          (str "namespaces outside `agent` still use the retired prefix: "
               (mapv second stale)))))
