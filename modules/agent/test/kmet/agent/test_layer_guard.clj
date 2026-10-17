(ns kmet.agent.test-layer-guard
  "Guard (engine.md §8): app business logic (kmet.agent.*, excluding
   kmet.agent.ui.tui.** and the extension-ABI injection) must not require the
   terminal/Tk front ends or the interactive mode."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(defn- app-source-files []
  (letfn [(walk [dir]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (walk "modules/agent/src/kmet/agent")
         (remove #(str/includes? (str %) "/ui/"))
         (remove #(str/includes? (str %) "/modes/"))
         (remove #(contains? #{"core.clj" "config.clj" "debug.clj" "extension.clj"
                               "package_manager.clj" "version.clj"}
                             (fs/file-name %)))
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
  ;; Known pre-existing violations of the app layer boundary. Remove an
  ;; entry when the corresponding leak is fixed; new violations fail this
  ;; test.
  {"kmet.agent.extensions.core" ["kmet.tui."]})

(defn- front-end-source-files
  "Every mode and app-UI namespace file (the front-end layers)."
  []
  (letfn [(walk [dir]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (concat (walk "modules/agent/src/kmet/agent/modes")
                 (walk "modules/agent/src/kmet/agent/ui"))
         (map str)
         (filter #(re-find #"\.clj[ca]?$" %))
         (sort))))

(deftest app-layer-does-not-require-front-ends
  (testing "no new kmet.agent namespace reaches the TUI/Tk/interactive layers"
    (doseq [f (app-source-files)]
      (let [ns-name (-> f (str/replace #"^modules/agent/src/" "")
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
                                            (str/starts-with? d "kmet.agent.modes.")))
                                      deps))]
        (is (empty? forbidden)
            (str ns-name " requires forbidden namespaces: " (vec (sort forbidden))))))))

(deftest front-ends-drive-the-runtime-not-the-loop
  (testing "modes and app UI reach the engine through the session runtime"
    (let [files (front-end-source-files)]
      (is (seq files) "no front-end sources scanned — check the walk roots")
      (is (some #(str/includes? % "/modes/") files) "modes tree not scanned")
      (is (some #(str/includes? % "/ui/") files) "app UI tree not scanned")
      (doseq [f files]
        (let [forbidden (filter #(str/starts-with? % "kmet.agent.loop")
                                (required-kmet f))]
          (is (empty? forbidden)
              (str f " requires " (vec forbidden)
                   " — front ends drive kmet.agent.session.runtime instead")))))))
