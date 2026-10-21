(ns kmet.test-module-deps
  "Guard (global organization): module dependencies must flow downward.

   Two views of the same rule: each module's deps.edn may name only
   lower-ranked modules, and each *namespace* may require only a file in a
   strictly lower-ranked module (its own module is fine). The second is the
   one the compiler never checks: a `kmet.app.*` namespace requiring a
   `kmet.agent.*` helper is one line away at all times."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kmet.tasks.changed :as changed]
            [kmet.tasks.modules :as modules]
            [kmet.tasks.source-reader :as reader]))

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
;; ─── the require view ─────────────────────────────────────────────────────

(defn- ns-file
  "The source file holding NS-SYM under classpath root SRC, or nil when no
   module owns the namespace (third-party code, kmet.tasks.*, an extension)."
  [src ns-sym]
  (let [rel (-> (str ns-sym)
                (str/replace "." "/")
                (str/replace "-" "_"))
        base (fs/path src rel)]
    (some (fn [ext]
            (let [path (str base ext)]
              (when (fs/exists? path) path)))
          [".clj" ".cljc"])))

(defn- module-owner
  "The manifest module whose src tree holds NS-SYM, or nil."
  [ns-sym]
  (some (fn [mod] (when (ns-file (:src mod) ns-sym) mod)) (modules/modules)))

(defn- module-sources
  "Every module source file as {:module mod :file path}."
  []
  (for [mod (modules/modules)
        file (->> (fs/glob (:src mod) "**/*.{clj,cljc}") (map str) sort)]
    {:module mod :file file}))

(deftest module-requires-flow-downward
  (testing "a namespace requires only a strictly lower-ranked module's file"
    (doseq [{:keys [module file]} (module-sources)
            :let [ns-form (reader/read-ns-form file "module-require guard")
                  up (for [dep (if ns-form (changed/ns-requires ns-form) #{})
                           :let [owner (module-owner dep)]
                           :when (and owner
                                      (not= (:name owner) (:name module))
                                      (>= (:rank owner) (:rank module)))]
                       [dep (:name owner) (:rank owner)])]]
      (is (some? ns-form)
          (str file " has no ns form — the require guard cannot read it"))
      (is (empty? up)
          (str file " (" (:name module) ", rank " (:rank module)
               ") requires upward: " (vec up))))))
