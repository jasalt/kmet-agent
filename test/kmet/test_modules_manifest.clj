(ns kmet.test-modules-manifest
  "Guard: modules.edn is the workspace manifest (kmet.tasks.modules) and the
   tasks read it directly. bb.edn, deps.edn and modules/*/deps.edn keep
   literal lists because tools.deps readers run before any code exists — this
   guard holds those literals to the manifest, so a module or extension
   added to one place cannot silently miss the others."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.modules :as modules]))

(defn- read-edn [path] (edn/read-string (slurp path)))

(deftest manifest-shape
  (testing "the manifest lists real trees with a rank each"
    (let [ms (modules/modules)]
      (is (<= 5 (count ms)) "the module list is real")
      (is (<= 3 (count (modules/extensions))) "the extension list is real")
      (is (seq (modules/roots)) "the extra root list is real")
      (is (seq (modules/test-root-paths)) "a test root is flagged")
      (doseq [mod ms]
        (is (fs/directory? (:src mod)) (str (:name mod) " src tree"))
        (is (fs/directory? (:test mod)) (str (:name mod) " test tree"))
        (is (int? (:rank mod)) (str (:name mod) " has a rank")))
      (doseq [ext (modules/extensions)]
        (is (fs/directory? (:src ext)) (str (:name ext) " src tree"))
        (is (fs/directory? (:test ext)) (str (:name ext) " test tree"))))))

(deftest bb-edn-paths-match-the-manifest
  (testing "bb.edn :paths is the manifest classpath list"
    (is (= (modules/classpath-paths) (:paths (read-edn "bb.edn"))))))

(deftest deps-edn-embed-paths-match-the-manifest
  (testing "deps.edn :jolt/build :embed is the manifest embed list"
    (is (= (modules/embed-paths) (get-in (read-edn "deps.edn") [:jolt/build :embed])))))

(deftest dependencies-edn-point-at-manifest-modules
  (testing "every module has a deps.edn whose :paths is the manifest layout"
    (let [ext-paths (mapv #(str "../../" %) (modules/extension-paths))]
      (doseq [mod (modules/modules)]
        (let [dep-edn (read-edn (str (fs/path "modules" (:name mod) "deps.edn")))
              expected (if (= "agent" (:name mod))
                         (into ["src" "test"] ext-paths)
                         ["src" "test"])]
          (is (= expected (:paths dep-edn))
              (str "modules/" (:name mod) "/deps.edn :paths"))))))
  (testing "the root deps.edn pulls the app module (which pulls the rest)"
    (let [agent-mod (some #(when (= "agent" (:name %)) %) (modules/modules))
          agent-root (str/replace (str (fs/parent (:src agent-mod))) "\\" "/")]
      (is (= {:local/root agent-root} (get (:deps (read-edn "deps.edn")) 'kmetia/agent))))))

(deftest derived-test-registry-is-nonempty
  (testing "the scan covers every test root"
    (let [files (modules/test-namespace-files)]
      (is (<= 150 (count files)) "the scan found the suite")
      (is (= (mapv :ns files) (distinct (mapv :ns files)))
          "no duplicate namespaces")
      (doseq [root (modules/test-roots)]
        (is (some #(str/starts-with? (str/replace (:file %) "\\" "/")
                                     (str (str/replace root "\\" "/") "/"))
                  files)
            (str "no test namespaces found under " root))))))
