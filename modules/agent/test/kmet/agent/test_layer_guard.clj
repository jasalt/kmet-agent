(ns kmet.agent.test-layer-guard
  "Guard (app layer boundary): app business logic (kmet.agent.*, excluding
   kmet.agent.ui.tui.** and the extension-ABI injection) must not require the
   terminal/Tk front ends or the interactive mode, and the front-end layers
   (kmet.agent.modes.**, kmet.agent.ui.**) drive the engine only through
   kmet.app.session.runtime."
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
                       ;; the app module's business logic — the loop cluster and
                       ;; the session layer moved there, and landing 11 renamed
                       ;; them to kmet.app.* with it
                       "modules/app/src/kmet/app"])
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
      (str/replace #"^modules/[^/]+/src/" "")
      (str/replace #"\.clj[ca]?$" "")
      (str/replace #"/" ".")
      (str/replace #"_" "-")))

(defn- source-files-under
  "Every Clojure source file under DIR, path-normalized and sorted. A missing
   DIR yields nothing instead of throwing: the callers assert which roots they
   expect files from (`<root> not scanned`), so a renamed or emptied tree fails
   by name rather than as a NoSuchFileException from the walk."
  [dir]
  (when (fs/exists? dir)
    (letfn [(walk [d]
              (mapcat (fn [f]
                        (if (fs/directory? f) (walk f) [f]))
                      (fs/list-dir d)))]
      (->> (walk dir)
           (map slash)
           (filter #(re-find #"\.clj[ca]?$" %))
           (sort)))))

(defn- mode-source-files
  "Every entry-mode namespace file."
  []
  (source-files-under "modules/agent/src/kmet/agent/modes"))

(def ^:private ui-roots
  "Where the app-UI layer's files are: the `ui` module, in two subtrees — the
   files that moved under `kmet.ui.*`, and the one that kept its
   `kmet.agent.ui.*` name because shipped extensions require it by that name.
   `agent` holds no UI tree since landing 5i moved `resource_config`, its last
   file."
  ["modules/ui/src/kmet/ui"
   "modules/ui/src/kmet/agent/ui"])

(defn- ui-source-files
  "Every app-UI namespace file, both halves. One layer, so every rule below
   applies to all of it: the rank guard catches a `ui` file reaching `agent`,
   but not a `ui` file reaching `kmet.app.loop` (rank 2, below it)."
  []
  (sort (mapcat source-files-under ui-roots)))

(defn- ui-mode-files
  "The front ends that live under `ui` after 5l-iii: the TUI's provider/mode row
   (`kmet.ui.tui`) and its interactive cluster. They are modes — they own a run
   and drive the engine — so the widget-scoped `:ui` rules do not bind them."
  []
  (filter #(or (= "modules/ui/src/kmet/ui/tui.clj" %)
               (str/starts-with? % "modules/ui/src/kmet/ui/tui/interactive"))
          (ui-source-files)))

(defn- front-end-source-files
  "Every mode and app-UI namespace file (the front-end layers)."
  []
  (sort (concat (mode-source-files) (ui-source-files))))

(def ^:private libs-namespaces
  "The rank-0 leaves by namespace. A `kmet.tui.*` name under `modules/libs` is
   not the TUI module: the theme-name handling and the fuzzy matcher are shared
   leaves the app and the TUI both read (the shared-leaf rule put them there),
   and the prefix is only where they were first written. The rule below is about
   the *module*, so it reads the tree rather than the prefix alone."
  (delay (into #{} (map path->ns) (source-files-under "modules/libs/src"))))

(deftest app-layer-does-not-require-front-ends
  (testing "no new kmet.agent namespace reaches the TUI/Tk/interactive layers"
    (let [shared @libs-namespaces]
      (doseq [f (app-source-files)
              :let [ns-name (path->ns f)
                    deps (required-kmet f)
                    forbidden (remove shared
                                      (filter (fn [d]
                                                (or (str/starts-with? d "kmet.tui.")
                                                    (str/starts-with? d "kmet.tk.")
                                                    (str/starts-with? d "kmet.ui.")
                                                    (str/starts-with? d "kmet.agent.modes.")))
                                              deps))]]
        (is (empty? forbidden)
            (str ns-name " requires forbidden namespaces: " (vec (sort forbidden))))))))

(def ^:private front-end-forbidden
  "Prefixes a front end must not require, and the files the rule binds — the
   scope is the point:

   - `:front-ends` (every mode and UI file): the loop driver and its stages, and
     the compaction stage. A front end reaches the engine through the session
     runtime, not the machinery under it (the pure `kmet.app.compaction.core`
     helpers are fine — the footer reads tokens).
   - `:ui` (the app-UI widgets alone): the session driver. It starts work —
     prompts, turns, session switching, disposal — and a mode owns a run and may
     drive it, while a widget reads the engine instead
     (`kmet.app.session.runtime`, one rank below the driver). The TUI mode
     entry and its interactive cluster are front ends that *are* a mode: 5l-iii
     moved them under `ui`, so they keep the `:front-ends` rule and this one
     skips them (`ui-mode-files`)."
  [{:scope :front-ends
    :prefixes ["kmet.app.loop" "kmet.app.compaction.stage"]
    :why "front ends reach the engine through the session runtime"}
   {:scope :ui
    :prefixes ["kmet.app.session.driver"]
    :why "the UI layer reads the engine; the driver starts work, and a mode owns a run"}])

(deftest front-ends-drive-the-runtime-not-the-loop
  (testing "modes and app UI reach the engine through the session runtime"
    (let [files (front-end-source-files)]
      (is (seq files) "no front-end sources scanned — check the walk roots")
      (is (some #(str/starts-with? % "modules/agent/src/kmet/agent/modes/") files)
          "modes tree not scanned")
      ;; every UI root must contribute a file, named by the root: a root that
      ;; stops matching (moved file, renamed dir) would otherwise narrow the
      ;; scan silently, and the rules below would pass on a smaller set
      (doseq [root ui-roots]
        (is (some #(str/starts-with? % root) files) (str root " not scanned")))
      (doseq [{:keys [scope prefixes why]} front-end-forbidden
              f (if (= :ui scope) (remove (set (ui-mode-files)) (ui-source-files)) files)
              :let [forbidden (filter (fn [d] (some #(str/starts-with? d %) prefixes))
                                      (required-kmet f))]]
        (is (empty? forbidden)
            (str f " requires " (vec forbidden) " — " why))))))

(deftest app-ui-does-not-require-entry-modes
  (testing "app UI components never reach up into kmet.agent.modes — the UI layer
            sits below the modes, so it takes its data from the caller or the
            runtime, never from an entry mode"
    (let [files (ui-source-files)]
      (is (seq files) "no app UI sources scanned — check the walk roots")
      (doseq [f files
              :let [ns-name (path->ns f)
                    forbidden (filter #(str/starts-with? % "kmet.agent.modes.")
                                      (required-kmet f))]]
        (is (empty? forbidden)
            (str ns-name " requires " (vec forbidden)))))))

(def ^:private loop-module-edges
  "The only allowed kmet.agent.loop.* edges; a subset check, so the map can
   name modules that do not exist yet."
  {"kmet.app.loop.message" #{}
   "kmet.app.loop.state" #{"kmet.app.loop.message"}
   "kmet.app.loop.completion" #{"kmet.app.loop.state" "kmet.app.loop.message"}
   "kmet.app.loop.tool-exec" #{"kmet.app.loop.state" "kmet.app.loop.message"}
   "kmet.app.loop" #{"kmet.app.loop.message" "kmet.app.loop.state"
                     "kmet.app.loop.completion" "kmet.app.loop.tool-exec"
                     "kmet.app.loop.stages"}})

(defn- loop-source-files
  "The loop driver and its submodules."
  []
  (->> (app-source-files)
       (filter #(or (= "loop.clj" (fs/file-name %))
                    (str/includes? % "/app/loop/")))
       ;; the kmet2 run machinery beside them (`driver`, `stages`) is not a
       ;; loop submodule: it is the engine a front end drives
       (remove #(contains? #{"driver.clj" "stages.clj"} (fs/file-name %)))
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
                :when (str/starts-with? dep "kmet.app.loop")
                :when (not (contains? #{"kmet.app.loop-guard"
                                        "kmet.app.retry"} dep))]
          (is (contains? (get loop-module-edges ns-name #{}) dep)
              (str ns-name " may not require " dep)))))))

(deftest the-loop-entry-point-is-pinned
  (testing "kmet.app.loop exposes only the driver entry points"
    ;; ns-publics needs the namespace loaded: a filtered test run loads only
    ;; the requested namespaces, so require it here instead of relying on
    ;; another test namespace having pulled it in.
    (require 'kmet.app.loop)
    (is (= '#{make-agent-state run-agent-turn cancel-turn}
           (set (keys (ns-publics 'kmet.app.loop)))))))


