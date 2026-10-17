(ns kmet.test-module-deps
  "Guard (global organization): module dependencies must flow downward."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.tasks.modules :as modules]))

(def ^:private module-rank
  "Higher layers appear later; a module may depend only on lower-ranked ones.
   The ranks live in modules.edn (kmet.tasks.modules)."
  (into {} (map (juxt :name :rank) (modules/modules))))

(defn- module-roots
  "Every modules/<name> carrying a deps.edn. fs/list-dir already returns
   modules/<name>, so the root path is used as-is: re-prefixing \"modules\"
   builds modules/modules/... and silently scans nothing."
  []
  (->> (fs/list-dir "modules")
       (filter #(fs/exists? (fs/path % "deps.edn")))
       (sort-by str)))

(defn- module-deps
  "The sibling module names ROOT depends on, taken from its deps.edn
   :local/root entries."
  [root]
  (->> (get (edn/read-string (slurp (fs/file root "deps.edn"))) :deps {})
       (keep (fn [[_ spec]]
               (when (map? spec)
                 (:local/root spec))))
       (map #(last (str/split (str %) #"/")))
       vec))

(deftest module-dependencies-flow-downward
  (testing "every module depends only on lower layers"
    (let [roots (module-roots)]
      (is (seq roots)
          "no module roots scanned — check the module path handling")
      (doseq [root roots
              :let [module (str (fs/file-name root))]]
        (is (contains? module-rank module)
            (str module " has no entry in module-rank"))
        (doseq [dep (module-deps root)]
          (is (contains? module-rank dep)
              (str module " depends on unknown module " dep))
          (when (and (contains? module-rank module)
                     (contains? module-rank dep))
            (is (< (module-rank dep) (module-rank module))
                (str module " (" (module-rank module) ") must not depend on "
                     dep " (" (module-rank dep) ")"))))))))