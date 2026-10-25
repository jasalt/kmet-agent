(ns kmet.test-front-end-policy
  "Guard: kmet1's front ends name the process's resource policy when they boot
   the Engine.

   `:load-resources!` and `:expand-input` have no defaults on purpose — the
   process owns where its packages and skill commands come from — so a front
   end that drops one stops loading skills or expanding skill commands without
   failing anything. This holds the wiring the way `kmet.test-seam-requires`
   holds the edges: statically, per call site.

   Print mode is the exception the table carries: it expands its message before
   it prompts, so it names the loader only. A front end that stops passing a
   map literal at the `bootstrap` call fails the guard rather than passing
   silently — re-teach it when that happens. The require graph has no edge
   from here to the front ends, so this runs in the full suite (`bb test`), not
   in the changed-file loop of a front-end edit."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(def ^:private front-ends
  "Front-end file → the `bootstrap` options it must name, with the exact value
   another file cannot quietly substitute. The TUI is not here: it reads an
   agent handle (landing 8), so its policy is split below."
  {"modules/agent/src/kmet/agent/modes/print.clj"
   {:load-resources! 'packages/load-resources!}
   "modules/agent/src/kmet/agent/modes/rpc.clj"
   {:load-resources! 'packages/load-resources!
    :expand-input 'packages/expand-input}})

(def ^:private handle-front-ends
  "The policy's two halves where a handle front end names them: the layout
   loads the process's resources before it creates its agent, and the handle
   factory names the expansion chain the engine applies."
  {"modules/ui/src/kmet/ui/tui/interactive/layout.clj" 'packages/load-resources!
   "modules/app/src/kmet/app/loop/driver.clj" 'packages/expand-input})

(defn- read-forms
  "Every top-level form of PATH, read once."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (read {:eof ::eof :read-cond :allow} r)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn- bootstrap-call?
  "A call to `bootstrap`, whatever alias the namespace was required under: the
   session driver is the one namespace that constructs an engine, and the guard
   is about the options a front end names, not about the alias it uses."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "bootstrap" (name (first form)))))

(defn- bootstrap-opts
  "The map literal PATH passes to its `bootstrap` call, or nil."
  [path]
  (->> (read-forms path)
       (tree-seq coll? seq)
       (some (fn [form]
               (when (bootstrap-call? form)
                 (nth form 2 nil))))))

(t/deftest every-front-end-names-the-processes-resource-policy
  (doseq [[path expected] front-ends]
    (t/testing path
      (t/is (fs/exists? path) "the front end is where the table says")
      (let [opts (bootstrap-opts path)]
        (t/is (map? opts) "the bootstrap options are a map literal")
        (t/is (= expected (select-keys opts [:load-resources! :expand-input]))
              "the front end names its resource policy, and only that")))))

(defn- ns-requires
  "The namespaces PATH's ns form requires."
  [path]
  (->> (tree-seq coll? seq (first (read-forms path)))
       (filter vector?)
       (map first)
       (filter symbol?)
       set))

(def ^:private tui-sources
  "The TUI's front-end files: the row/provider entry beside the provider
   directory, and everything in it. Both roots, because `fs/glob`'s walk of the
   directory does not include its sibling file — a require in the row would
   otherwise go unseen."
  ["modules/ui/src/kmet/ui/tui.clj"
   "modules/ui/src/kmet/ui/tui"])

(t/deftest the-tui-reads-a-handle-and-not-kmet1s-session-driver
  (let [files (->> (mapcat (fn [root]
                             (if (fs/directory? root)
                               (map str (fs/glob root "**.clj"))
                               [root]))
                           tui-sources)
                   sort
                   distinct)]
    (t/is (seq files) "the TUI's sources are where the guard looks")
    (t/is (some #(str/ends-with? % "ui/tui.clj") files)
          "the row file is scanned, not just the provider directory")
    (t/is (some #(str/includes? % "/interactive/layout.clj") files)
          "and the interactive cluster is scanned")
    (doseq [path files]
      (t/is (not (contains? (ns-requires path) 'kmet.app.session.driver))
            (str path " requires kmet1's session driver — the TUI reads a"
                 " handle and the shared lifecycle, not the engine constructor"
                 " that dies with kmet1's entry (T-4)")))))

(t/deftest a-handle-front-end-names-its-half-of-the-resource-policy
  (doseq [[path value] handle-front-ends]
    (t/testing path
      (t/is (fs/exists? path) "the front end is where the table says")
      (t/is (some #(= value %) (tree-seq coll? seq (read-forms path)))
            (str "it names " value)))))
