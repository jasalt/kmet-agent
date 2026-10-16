(ns kmet.test-module-deps
  "Guard (global organization): module dependencies must flow downward."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

(def ^:private module-rank
  "Higher layers appear later; a module may depend only on lower-ranked ones."
  {"reakt" 0
   "libs" 0
   "loader" 0
   "hikap" 1
   "ai" 1
   "tui" 2
   "tk" 2
   "app" 3})

(defn- module-deps [module]
  (let [deps (-> (str "modules/" module "/deps.edn")
                 slurp
                 edn/read-string)
        local (->> (get deps :deps {})
                   (keep (fn [[_ spec]]
                           (when (map? spec)
                             (:local/root spec))))
                   (map #(str/replace (fs/file-name %) (re-pattern "\\.\\.$") ""))
                   (map #(last (str/split % #"/"))))
        _ (assert (vector? local))]
    local))

(deftest module-dependencies-flow-downward
  (testing "every module depends only on lower layers"
    (doseq [module (sort-by str (fs/list-dir "modules"))]
      (when (fs/exists? (fs/path "modules" module "deps.edn"))
        (let [deps (module-deps module)]
          (doseq [dep deps]
            (is (contains? module-rank dep)
                (str module " depends on unknown module " dep))
            (is (< (module-rank dep) (module-rank module))
                (str module " must not depend on " dep))))))))