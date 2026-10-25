(ns kmet.test-no-global-node
  "Guard (kmet2 organization): the new modules never reach kmet1's process
   state.

   kmet2 replaced `kmet.libs.scope`'s one app-root store with a ctx tree
   (`new-app.md`): a session, a UI provider or a mode is a child context, not
   a registration on the process's single node. The store is deleted (landing
   4e), so this bans its name and `kmet.agent.app-runtime`'s — whose `ctx`
   resolves *the* root — as tripwires against reintroducing either, in the
   modules that must be kmet1-free: the kernel, the app, and (from landing 5)
   the front ends. `kmet.app.version` is a `kmet.agent.*` namespace that
   lives in the app module (the file moved, the namespace did not), so only
   the process-state edges are listed, not the prefix."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kmet.tasks.changed :as changed]
            [kmet.tasks.source-reader :as reader]))

(def ^:private new-module-srcs
  "The kmet2 modules. ui/ and mode/ are listed before they exist: adding the
   tree is enough for the guard to cover it."
  ["modules/srce/src" "modules/app/src" "modules/ui/src" "modules/mode/src"])

(def ^:private store-namespaces
  "kmet1's process-global state: the root-ctx resolver and the retired store."
  '#{kmet.agent.app-runtime kmet.agent.runtime kmet.libs.scope})

(defn- sources
  "Every source file in the existing kmet2 modules."
  []
  (->> new-module-srcs
       (filter #(fs/directory? %))
       (mapcat #(fs/glob % "**/*.{clj,cljc}"))
       (map str)
       sort))

(deftest the-guard-sees-the-new-modules
  (is (<= 2 (count (filter #(fs/directory? %) new-module-srcs)))
      "the kernel and the app module exist")
  (is (seq (sources)) "the guard found the kmet2 sources"))

(deftest no-new-module-requires-the-process-store
  (doseq [file (sources)
          :let [ns-form (reader/read-ns-form file "no-global-node guard")
                requires (if ns-form (changed/ns-requires ns-form) #{})
                store (filter store-namespaces requires)]]
    (is (some? ns-form) (str file " has no ns form — the guard cannot read it"))
    (is (empty? store)
        (str file " requires kmet1's process store " (vec store)
             " — kmet2 code takes its ctx"))))

(deftest no-new-module-calls-the-root-node
  (doseq [file (sources)]
    (is (not (str/includes? (slurp file) "runtime/node"))
        (str file " mentions runtime/node — the store is not a ctx"))))
