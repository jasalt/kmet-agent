(ns kmet.test-front-end-policy
  "Guard: kmet1's front ends name the process's resource policy when they boot
   the Engine.

   `:load-resources!` and `:expand-input` have no defaults on purpose — the
   process owns where its packages and skill commands come from — so a front
   end that drops one stops loading skills or expanding skill commands without
   failing anything. This holds the wiring honest the way
   `kmet.test-seam-requires` holds the edges: statically, per call site.

   Print mode is the exception the table carries: it expands its message before
   it prompts and never hands the Engine a chain, so it names the loader only.
   A front end that stops using a map literal at the `bootstrap` call fails the
   guard rather than passing it silently — re-teach the guard when that
   happens."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :as t]
            [kmet.tasks.changed :as changed]))

(def ^:private front-ends
  "Front-end file → the `bootstrap` options it must name, with the exact value
   another file cannot quietly substitute."
  {"modules/agent/src/kmet/agent/modes/print.clj"
   {:load-resources! 'packages/load-resources!}
   "modules/agent/src/kmet/agent/modes/rpc.clj"
   {:load-resources! 'packages/load-resources!
    :expand-input 'packages/expand-input}
   "modules/agent/src/kmet/agent/modes/interactive/layout.clj"
   {:load-resources! 'packages/load-resources!
    :expand-input 'packages/expand-input}})

(def ^:private policy-keys #{:load-resources! :expand-input})

(defn- read-forms
  "Every top-level form of PATH, read once."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (loop [forms []]
      (let [form (read {:eof ::eof :read-cond :allow} r)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn- bootstrap-opts
  "The map literal PATH passes to its `runtime/bootstrap` call, or nil."
  [path]
  (->> (read-forms path)
       (tree-seq coll? seq)
       (some (fn [form]
               (when (and (seq? form) (= 'runtime/bootstrap (first form)))
                 (nth form 2 nil))))))

(t/deftest every-front-end-names-the-processes-resource-policy
  (doseq [[path expected] front-ends]
    (t/testing path
      (t/is (fs/exists? path) "the front end is where the table says")
      (let [opts (bootstrap-opts path)]
        (t/is (map? opts) "the bootstrap options are a map literal")
        (t/is (= expected (select-keys opts policy-keys))
              "the front end names its resource policy, and only that")))
    (let [ns-form (changed/read-ns-form path)]
      (t/is (some #{'kmet.agent.packages} (changed/ns-requires ns-form))
            "and it requires the namespace those names live in"))))
