(ns kmet.test-test-ns-registration
  "Guard (global organization): every test-bearing namespace must be registered."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.set :as set]
            [kmet.tasks.runner :as runner]))

(defn- test-roots
  "Every test root: the repo-root test/ tree plus each module's and
   extension's own test/ tree. fs/list-dir already returns modules/<name>
   and extensions/<name>, so the root path is used as-is — re-prefixing
   built modules/modules/... and silently skipped these trees."
  []
  (concat ["test"]
          (for [root (fs/list-dir "modules")
                :let [d (fs/path root "test")]
                :when (fs/directory? d)]
            (str d))
          (for [root (fs/list-dir "extensions")
                :let [d (fs/path root "test")]
                :when (fs/directory? d)]
            (str d))))

(defn- ns-symbol [root path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace (re-pattern (str "^" (java.util.regex.Pattern/quote (str/replace (str root) "\\" "/")) "/")) "")
      (str/replace #"\.clj[ac]?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn- test-bearing-namespaces []
  (->> (test-roots)
       (mapcat (fn [root]
                 (->> (file-seq (java.io.File. root))
                      (filter #(re-find #"\.clj[ac]?$" (str %)))
                      (filter #(str/includes? (slurp %) "deftest"))
                      (map #(ns-symbol root %)))))
       (map symbol)
       set))

(deftest test-roots-cover-module-and-extension-trees
  (testing "every modules/*/test and extensions/*/test tree is scanned"
    (let [roots (set (map #(str/replace (str %) "\\" "/") (test-roots)))]
      (doseq [dir (->> (concat (fs/list-dir "modules") (fs/list-dir "extensions"))
                       (map #(fs/path % "test"))
                       (filter fs/directory?)
                       (map #(str/replace (str %) "\\" "/")))]
        (is (contains? roots dir)
            (str "test root not scanned: " dir))))))

(deftest registered-test-namespaces-cover-test-files
  (testing "all test-bearing namespaces appear in runner/all-namespaces"
    (let [found (test-bearing-namespaces)
          registered (set runner/all-namespaces)
          missing (sort (set/difference found registered))]
      (is (seq found)
          "no test-bearing namespaces found — the scan is vacuous")
      (is (empty? missing)
          (str "unregistered test-bearing namespaces: " (vec missing))))))