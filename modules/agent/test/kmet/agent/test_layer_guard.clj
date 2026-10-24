(ns kmet.agent.test-layer-guard
  "Guard (app layer boundary): app business logic (kmet.agent.*, excluding
   kmet.agent.ui.tui.** and the extension-ABI injection) must not require the
   terminal/Tk front ends or the interactive mode, and the front-end layers
   (kmet.agent.modes.**, kmet.agent.ui.**) drive the engine only through
   kmet.agent.session.runtime."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.tasks.source-reader :as sr]))

(defn- slash
  "PATH with the platform separator normalized to / — the guards strip and
   compare path fragments, which must not depend on the host separators."
  [path]
  (str/replace (str path) "\\" "/"))

(defn- app-source-files []
  (letfn [(walk [dir]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir dir)))]
    (->> (mapcat walk ["modules/agent/src/kmet/agent"
                       ;; the app module's kmet.agent.* namespaces are the app's
                       ;; business logic too (the loop cluster and the session
                       ;; layer moved there; the two-axes rule keeps the names)
                       "modules/app/src/kmet/agent"])
         (remove #(str/includes? (slash %) "/ui/"))
         (remove #(str/includes? (slash %) "/modes/"))
         ;; cli.clj is the composition root: it wires the modes and the TUI
         ;; engines, so it legitimately requires them. Everything else in the
         ;; app tree is scanned — core.clj included: the bootstrap above cli.clj
         ;; requires neither app and no front end.
         (remove #(= "cli.clj" (fs/file-name %)))
         (filter #(re-find #"\.clj[ca]?$" (str %)))
         (map slash))))

(defn- ns-form [path]
  (sr/read-ns-form path "layer guard"))

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

(defn- path->ns
  "The namespace named by a source file path under a module's src tree."
  [path]
  (-> path
      (str/replace #"^modules/(agent|app)/src/" "")
      (str/replace #"\.clj[ca]?$" "")
      (str/replace #"/" ".")
      (str/replace #"_" "-")))

(def ^:private temporary-leaks
  ;; Known pre-existing violations of the app layer boundary. Remove an
  ;; entry when the corresponding leak is fixed; new violations fail this
  ;; test. Empty since the extension resource loader moved its theme call
  ;; behind the extensions-context call-time door
  ;; (kmet.agent.extensions.context/load-themes-from-dir!).
  {})

(defn- source-files-under
  "Every Clojure source file under DIR, path-normalized and sorted."
  [dir]
  (letfn [(walk [d]
            (mapcat (fn [f]
                      (if (fs/directory? f) (walk f) [f]))
                    (fs/list-dir d)))]
    (->> (walk dir)
         (map slash)
         (filter #(re-find #"\.clj[ca]?$" %))
         (sort))))

(defn- mode-source-files
  "Every entry-mode namespace file."
  []
  (source-files-under "modules/agent/src/kmet/agent/modes"))

(def ^:private ui-roots
  "Where the app-UI layer's files are. `agent` still holds the half written
   against kmet1's runtime; landing 5 moved the rest into the `ui` module under
   `kmet.ui.*` — and one file kept its `kmet.agent.ui.*` name, because shipped
   extensions require it by that name, so the module has two UI subtrees rather
   than one."
  ["modules/agent/src/kmet/agent/ui"
   "modules/ui/src/kmet/ui"
   "modules/ui/src/kmet/agent/ui"])

(defn- ui-source-files
  "Every app-UI namespace file, both halves. One layer, so every rule below
   applies to all of it: the rank guard catches a `ui` file reaching `agent`,
   but not a `ui` file reaching `kmet.agent.loop` (rank 2, below it)."
  []
  (sort (mapcat source-files-under ui-roots)))

(defn- front-end-source-files
  "Every mode and app-UI namespace file (the front-end layers)."
  []
  (sort (concat (mode-source-files) (ui-source-files))))

(deftest app-layer-does-not-require-front-ends
  (testing "no new kmet.agent namespace reaches the TUI/Tk/interactive layers"
    (doseq [f (app-source-files)]
      (let [ns-name (path->ns f)
            deps (required-kmet f)
            forbidden (remove (fn [d]
                                (and (contains? temporary-leaks ns-name)
                                     (some #(str/starts-with? d %) (get temporary-leaks ns-name))))
                              (filter (fn [d]
                                        (or (str/starts-with? d "kmet.tui.")
                                            (str/starts-with? d "kmet.tk.")
                                            (str/starts-with? d "kmet.ui.")
                                            (str/starts-with? d "kmet.agent.modes.")))
                                      deps))]
        (is (empty? forbidden)
            (str ns-name " requires forbidden namespaces: " (vec (sort forbidden))))))))

(def ^:private front-end-forbidden-prefixes
  "Namespaces a front end must reach through kmet.agent.session.runtime: the
   loop driver and its stages, and the compaction stage (the pure
   kmet.agent.compaction.core helpers are fine — the footer reads tokens)."
  ["kmet.agent.loop" "kmet.agent.compaction.stage"])

(def ^:private ui-forbidden-prefixes
  "Namespaces only a mode may reach. The session driver starts work — prompts,
   turns, session switching, disposal — and the UI layer reads the engine
   instead (kmet.agent.session.runtime, one rank below it). A mode owns a run
   and may drive it; a widget may not, whichever module it lives in."
  ["kmet.agent.session.driver"])

(deftest front-ends-drive-the-runtime-not-the-loop
  (testing "modes and app UI reach the engine through the session runtime"
    (let [files (front-end-source-files)]
      (is (seq files) "no front-end sources scanned — check the walk roots")
      (is (some #(str/includes? % "/modes/") files) "modes tree not scanned")
      (is (some #(str/includes? % "/ui/") files) "app UI tree not scanned")
      (is (some #(str/includes? % "modules/ui/src/kmet/ui/") files)
          "the ui module's half not scanned")
      (is (some #(str/includes? % "modules/ui/src/kmet/agent/ui/") files)
          "the file that kept its kmet.agent.ui.* name not scanned")
      (doseq [f files]
        (let [forbidden (filter (fn [d]
                                  (some #(str/starts-with? d %)
                                        front-end-forbidden-prefixes))
                                (required-kmet f))]
          (is (empty? forbidden)
              (str f " requires " (vec forbidden)
                   " — front ends drive kmet.agent.session.runtime instead"))))
      (doseq [f (ui-source-files)]
        (let [forbidden (filter (fn [d]
                                  (some #(str/starts-with? d %) ui-forbidden-prefixes))
                                (required-kmet f))]
          (is (empty? forbidden)
              (str f " requires " (vec forbidden)
                   " — the UI layer reads kmet.agent.session.runtime; the driver
                      starts work and belongs to the modes")))))))

(def ^:private ui-temporary-leaks
  ;; Known app-UI namespaces and the entry-mode prefixes they may require.
  ;; The UI layer sits below the modes (a mode assembles components, not the
  ;; other way round), so an entry here is debt: move the dependency down
  ;; instead of adding one. Empty since the theme controller moved into the
  ;; UI layer.
  {})

(deftest app-ui-does-not-require-entry-modes
  (testing "app UI components never reach up into kmet.agent.modes"
    (let [files (ui-source-files)]
      (is (seq files) "no app UI sources scanned — check the walk roots")
      (doseq [f files
              :let [ns-name (path->ns f)
                    allowed (get ui-temporary-leaks ns-name #{})]]
        (let [forbidden (remove (fn [d]
                                  (some #(str/starts-with? d %) allowed))
                                (filter #(str/starts-with? % "kmet.agent.modes.")
                                        (required-kmet f)))]
          (is (empty? forbidden)
              (str ns-name " requires " (vec forbidden)
                   " — UI takes its data from the caller or the runtime, "
                   "never from an entry mode")))))))

(def ^:private loop-module-edges
  "The only allowed kmet.agent.loop.* edges; a subset check, so the map can
   name modules that do not exist yet."
  {"kmet.agent.loop.message" #{}
   "kmet.agent.loop.state" #{"kmet.agent.loop.message"}
   "kmet.agent.loop.completion" #{"kmet.agent.loop.state" "kmet.agent.loop.message"}
   "kmet.agent.loop.tool-exec" #{"kmet.agent.loop.state" "kmet.agent.loop.message"}
   "kmet.agent.loop" #{"kmet.agent.loop.message" "kmet.agent.loop.state"
                       "kmet.agent.loop.completion" "kmet.agent.loop.tool-exec"}})

(defn- loop-source-files
  "The loop driver and its submodules."
  []
  (->> (app-source-files)
       (filter #(or (= "loop.clj" (fs/file-name %))
                    (str/includes? % "/agent/loop/")))
       sort))

(deftest loop-modules-form-a-dag
  (testing "the loop submodules require only earlier modules (no cycles)"
    (let [files (loop-source-files)]
      (is (seq files) "no loop sources scanned — check the walk roots")
      (doseq [f files
              :let [ns-name (path->ns f)]]
        (is (contains? loop-module-edges ns-name)
            (str "no DAG entry for " ns-name))
        (doseq [dep (required-kmet f)
                :let [dep (str dep)]
                :when (str/starts-with? dep "kmet.agent.loop")
                :when (not (contains? #{"kmet.agent.loop-guard"
                                        "kmet.agent.retry"} dep))]
          (is (contains? (get loop-module-edges ns-name #{}) dep)
              (str ns-name " may not require " dep)))))))

(deftest the-loop-entry-point-is-pinned
  (testing "kmet.agent.loop exposes only the driver entry points"
    ;; ns-publics needs the namespace loaded: a filtered test run loads only
    ;; the requested namespaces, so require it here instead of relying on
    ;; another test namespace having pulled it in.
    (require 'kmet.agent.loop)
    (is (= '#{make-agent-state run-agent-turn cancel-turn}
           (set (keys (ns-publics 'kmet.agent.loop)))))))


