(ns kmet.app.test-layer-guard
  "Guard (engine.md §8): app business logic (kmet.app.*, excluding
   kmet.app.ui.** and the extension-ABI injection) must not require the
   terminal/Tk front ends or the interactive mode."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- app-source-files []
  (letfn [(walk [dir]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (walk "modules/app/src/kmet/app")
         (remove #(str/includes? (str %) "/ui/"))
         (filter #(re-find #"\.clj[ca]?$" (str %)))
         (map str))))

(defn- ns-form [path]
  (try (read-string (slurp path))
       (catch Exception e
         (throw (ex-info (str "layer guard cannot read " path) {} e)))))

(defn- required-kmet [path]
  (when-let [form (ns-form path)]
    (when (and (seq? form) (= 'ns (first form)))
      (->> (for [clause (rest form)
                 :when (and (seq? clause) (#{:require :require-macros} (first clause)))
                 spec (rest clause)]
             (cond
               (symbol? spec) spec
               (vector? spec) (first spec)
               (seq? spec) (first spec)))
           (map str)
           (filter #(str/starts-with? % "kmet."))
           distinct))))

(def ^:private temporary-leaks
  ;; Known pre-existing violations (engine.md §3.6/§5 finding F). Remove an
  ;; entry when the corresponding leak is fixed; new violations fail this
  ;; test.
  {"kmet.app.keybindings" ["kmet.tui."]
   "kmet.app.extensions" ["kmet.tui."]
   "kmet.app.extensions.context" ["kmet.tui." "kmet.modes."]})

(deftest app-layer-does-not-require-front-ends
  (testing "no new kmet.app namespace reaches the TUI/Tk/interactive layers"
    (doseq [f (app-source-files)]
      (let [ns-name (-> f (str/replace #"^modules/app/src/" "")
                        (str/replace #"\.clj[ca]?$" "")
                        (str/replace #"/" ".")
                        (str/replace #"_" "-"))
            deps (required-kmet f)
            forbidden (remove (fn [d]
                                (and (contains? temporary-leaks ns-name)
                                     (some #(str/starts-with? d %) (get temporary-leaks ns-name))))
                              (filter (fn [d]
                                        (or (str/starts-with? d "kmet.tui.")
                                            (str/starts-with? d "kmet.tk.")
                                            (str/starts-with? d "kmet.modes.")))
                                      deps))]
        (is (empty? forbidden)
            (str ns-name " requires forbidden namespaces: " (vec (sort forbidden))))))))
