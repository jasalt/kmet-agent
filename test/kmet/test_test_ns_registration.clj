(ns kmet.test-test-ns-registration
  "Guard (global organization): every test-bearing namespace must be registered."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.set :as set]
            [kmet.tasks.runner :as runner]))

(defn- test-roots []
  (concat ["test"]
          (for [m (fs/list-dir "modules")
                :let [d (fs/path "modules" m "test")]
                :when (fs/directory? d)]
            (str "modules/" m "/test"))
          (for [e (fs/list-dir "extensions")
                :let [d (fs/path "extensions" e "test")]
                :when (fs/directory? d)]
            (str "extensions/" e "/test"))))

(defn- ns-symbol [root path]
  (-> (str path)
      (str/replace (re-pattern (str "^" (java.util.regex.Pattern/quote root) "/")) "")
      (str/replace #"\.clj[ac]?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")))

(defn- test-bearing-namespaces []
  (->> (test-roots)
       (mapcat (fn [root]
                 (file-seq (java.io.File. root))))
       (filter #(re-find #"\.clj[ac]?$" (str %)))
       (filter #(str/includes? (slurp %) "deftest"))
       (map #(ns-symbol (first (filter (fn [r] (str/starts-with? (str %) r)) (test-roots))) %))
       (map symbol)
       set))

(deftest registered-test-namespaces-cover-test-files
  (testing "all test-bearing namespaces appear in runner/all-namespaces"
    (let [registered (set runner/all-namespaces)
          missing (sort (set/difference (test-bearing-namespaces) registered))]
      (is (empty? missing)
          (str "unregistered test-bearing namespaces: " (vec missing))))))