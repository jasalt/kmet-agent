(ns kmet.test-module-deps
  "Guard (global organization): module dependencies must flow downward, and a
   module must declare exactly the ones it requires.

   Three views of the same rule. Each module's deps.edn may name only
   lower-ranked modules; each *namespace* may require only a file in a
   strictly lower-ranked module (its own module is fine); and each module's
   `src` requires must appear in its deps.edn, with nothing there that `src`
   does not use. The second is the one the compiler never checks: a
   `kmet.app.*` namespace requiring a `kmet.agent.*` helper is one line away
   at all times. The third is what let `app` require `kmet.srce` for two
   landings without declaring it — the guard checked only the direction of the
   edges that were written down.

   A module's `:deps` is therefore its src edge list, not its classpath wish
   list. Test-only libraries live with the test project (the root deps.edn
   `:dev` alias): a module deps.edn is a `:local/root` dependency, and
   tools.deps never applies a dependency's aliases, so a `:test` alias there
   could not reach a test classpath. Test trees themselves are cross-cutting
   (`libs`' tests require `tui`, `ai`'s require `agent`) and often point
   upward; the classpath is their union, so they use modules no module
   deps.edn declares — ranking forbids most of those edges anyway."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.set :as set]
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

(defn- module-dep-map
  "ROOT's deps.edn :deps map."
  [root]
  (get (edn/read-string (slurp (fs/file root "deps.edn"))) :deps {}))

(defn- module-deps
  "The sibling module names ROOT depends on, taken from its deps.edn
   :local/root entries."
  [root]
  (->> (module-dep-map root)
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

;; ─── the declaration view ────────────────────────────────────────────────

(def ^:private reader-views
  "The two hosts' reader branches, read separately: `#?` resolves to the first
   matching branch per read, so a jolt-only require is invisible to the
   bb view and vice versa. A dep declared for either host's branch is used."
  [#{:bb :clj} #{:jolt :clj}])

(defn- root-requires
  "Every kmet.* namespace required by any source file under ROOT, in both
   hosts' views."
  [root]
  (->> (fs/glob root "**/*.{clj,cljc}")
       (map str)
       (mapcat (fn [file]
                 (mapcat (fn [features]
                           (when-let [ns-form
                                      (reader/read-ns-form
                                       file features "module declaration guard")]
                             (changed/ns-requires ns-form)))
                         reader-views)))
       set))

(defn- required-modules
  "The sibling module names the namespaces required under ROOT belong to."
  [module root]
  (->> (root-requires root)
       (keep module-owner)
       (map :name)
       set
       (#(disj % (:name module)))))

(deftest module-declarations-match-requires
  (testing "a module declares what its src requires, and nothing else"
    (doseq [{:keys [name src] :as module} (modules/modules)
            :let [deps-file (fs/file (fs/parent src) "deps.edn")
                  declared (set (module-deps (fs/parent src)))
                  from-src (required-modules module src)]]
      (is (fs/exists? deps-file)
          (str name " has no deps.edn at " deps-file))
      (is (empty? (set/difference from-src declared))
          (str name " requires modules it does not declare: "
               (vec (sort (set/difference from-src declared)))))
      (is (empty? (set/difference declared from-src))
          (str name " declares modules its src does not use: "
               (vec (sort (set/difference declared from-src))))))))

;; ─── the library view ────────────────────────────────────────────────────

(def ^:private lib-namespaces
  "The third-party libraries and the namespaces they provide. A module's :deps
   names every one of these its src requires and none it does not use — the
   same two directions as the module edges, one level down.

   A library outside this table cannot be checked (nothing knows which
   namespaces it owns), so declaring one fails with a message naming the table
   to extend; `host-provided-libs` excepts the two a module needs no
   declaration for."
  {'babashka/fs              #{"babashka.fs"}
   'babashka/process         #{"babashka.process"}
   'org.babashka/http-client #{"babashka.http-client"}
   'org.babashka/ffi         #{"babashka.ffi"}
   'org.babashka/sci         #{"sci"}
   'org.clojure/core.async   #{"clojure.core.async"}
   'org.clojure/data.json    #{"clojure.data.json"}
   'org.clojure/spec.alpha   #{"clojure.spec.alpha"}
   'org.clojure/tools.reader #{"clojure.tools.reader"}
   'metosin/malli            #{"malli"}
   'org.jline/jline-terminal #{"org.jline"}
   'dev.weavejester/cljfmt   #{"cljfmt"}
   'rewrite-clj/rewrite-clj  #{"rewrite-clj"}
   'parinferish/parinferish  #{"parinferish"}
   'borkdude/edamame         #{"edamame"}})

(def ^:private host-provided-libs
  "Libraries whose artifact is the host's, not a module's edge: a module needs
   no declaration to use them.

   - `org.babashka/ffi`: the standalone JVM FFI is added by the :jvm alias
     (babashka bundles babashka.ffi, Jolt has jolt.ffi, and no reader branch
     can say which host a module's FFI call runs on).
   - `org.babashka/sci`: the loader's design puts the backend artifact on the
     caller (loader.md §6.5) — babashka and Jolt bundle sci, and the host that
     mints contexts declares it (agent; jolt/deps.edn for Jolt)."
  #{'org.babashka/ffi 'org.babashka/sci})

(defn- ns-use
  "Every lib spec and imported class name in NS-FORM's :require and :import
   clauses."
  [ns-form]
  (let [clause (fn [k] (some #(when (and (seq? %) (= k (first %))) (rest %)) ns-form))]
    {:requires (set (keep (fn [spec]
                            (cond (symbol? spec) spec
                                  (vector? spec) (first spec)
                                  (seq? spec) (first spec)))
                          (clause :require)))
     :imports (set (mapcat (fn [spec]
                             (if (symbol? spec)
                               [(str spec)]
                               [(str (first spec) "." (second spec))]))
                           (clause :import)))}))

(defn- src-names
  "Every namespace required and every class imported by any source file under
   ROOT, in both hosts' views."
  [root]
  (->> (fs/glob root "**/*.{clj,cljc}")
       (map str)
       (mapcat (fn [file]
                 (keep (fn [features]
                         (some-> (reader/read-ns-form
                                  file features "module library guard")
                                 ns-use))
                       reader-views)))
       (reduce (fn [acc {:keys [requires imports]}]
                 (-> acc
                     (update :requires into requires)
                     (update :imports into imports)))
               {:requires #{} :imports #{}})))

(defn- under?
  "Whether the namespace or class NAME is ROOT or one of its children."
  [name root]
  (let [s (str name)]
    (or (= s root) (str/starts-with? s (str root ".")))))

(defn- used-libs
  "The table's libraries whose namespaces appear among the module's NAMES."
  [names]
  (into #{}
        (keep (fn [[lib roots]]
                (when (some (fn [name] (some (partial under? name) roots)) names)
                  lib)))
        lib-namespaces))

(defn- library-deps
  "The library coordinates in ROOT's deps.edn :deps. The module edges
   (:local/root) belong to the declaration view."
  [root]
  (into {}
        (remove (fn [[_ coord]] (contains? coord :local/root)))
        (module-dep-map root)))

(deftest module-libraries-match-requires
  (testing "a module declares the libraries its src requires, and only those"
    (doseq [{:keys [name src]} (modules/modules)
            :let [root (fs/parent src)
                  declared (set (keys (library-deps root)))
                  {:keys [requires imports]} (src-names src)
                  used (used-libs (into requires imports))
                  missing (set/difference used host-provided-libs declared)
                  unused (set/difference declared used)
                  unknown (set/difference declared (set (keys lib-namespaces)))]]
      (is (empty? missing)
          (str name " requires libraries it does not declare: "
               (vec (sort missing))))
      (is (empty? unused)
          (str name " declares libraries its src does not use: "
               (vec (sort unused))))
      (is (empty? unknown)
          (str name " declares libraries absent from lib-namespaces: "
               (vec (sort unknown)))))))

(def ^:private coord-keys
  "The parts of a dependency coordinate that do not depend on the manifest
   holding it — a :local/root path does, so it is not one."
  [:mvn/version :git/url :git/sha :git/tag])

(defn- pinned?
  "Whether COORD names an artifact (a Maven version or a git revision) rather
   than a sibling path."
  [coord]
  (boolean (some #(contains? coord %) coord-keys)))

(deftest dependency-coordinates-agree
  (testing "a library pinned in more than one manifest carries one coordinate"
    (let [manifests (into ["deps.edn" "bb.edn" "jolt/deps.edn"]
                          (map #(str (fs/parent (:src %)) "/deps.edn")
                               (modules/modules)))
          declarations (for [path manifests
                             [lib coord] (:deps (edn/read-string (slurp path)))
                             :when (and (map? coord) (pinned? coord))]
                         [lib (select-keys coord coord-keys) path])]
      (doseq [[lib decls] (group-by first declarations)
              :when (< 1 (count decls))
              :let [coords (set (map second decls))]]
        (is (= 1 (count coords))
            (str lib " is declared with " (count coords) " coordinates: "
                 (pr-str (into {} (map (juxt #(nth % 2) second) decls)))))))))

