(ns kmet.test-seam-requires
  "Guard: the seam directions a layered module cannot get from the compiler.

   kmet.ai is the lower layer — it must never require kmet.agent (the app).
   Inside it, the per-wire builders under kmet.ai.api are named only by
   kmet.ai.wires (a builder may reuse another's helpers), and the
   provider-event hooks are per-call options: kmet.ai.hooks stays gone and
   the slot helpers stay kmet.ai.auth's.

   Direction is checked statically, per edge — the pass-1 substitute for a
   runtime inject graph. kmet.test-module-deps owns the rank ordering between
   modules; this owns the edges inside one."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t :refer [is testing]]
   [kmet.tasks.changed :as changed]
   [kmet.tasks.modules :as modules]))

(defn- source-files
  "Every Clojure source file under ROOTS (default: the whole scan set)."
  ([] (source-files (modules/scan-roots)))
  ([roots]
   (->> roots
        (mapcat #(fs/glob % "**/*.{clj,cljc}"))
        (map str)
        sort)))

(defn- ns-info
  "PATH's `{:ns <symbol> :requires #{<symbol>}}`, or nil for a file whose
   first form is not an ns form."
  [path]
  (when-let [form (changed/read-ns-form path)]
    (when (and (seq? form) (= 'ns (first form)))
      {:ns (second form) :requires (changed/ns-requires form)})))

(def ^:private shared-wire-namespaces
  "The kmet.ai.api modules that are machinery rather than wires: a builder
   requires these, and nothing but kmet.ai.wires names a wire."
  '#{kmet.ai.api.shared kmet.ai.api.sse})

(defn- wire-require? [dep]
  (and (str/starts-with? (str dep) "kmet.ai.api.")
       (not (contains? shared-wire-namespaces dep))))

(t/deftest ai-never-reaches-up
  (testing "kmet.ai requires nothing from the app layer (pi: pi-ai has no pi-agent-core dependency)"
    (doseq [f (source-files ["modules/ai/src"])
            :let [{:keys [ns requires]} (ns-info f)
                  up (filter #(str/starts-with? (str %) "kmet.agent") requires)]
            :when (seq up)]
      (is false (str f " (" ns ") must not require the app layer, found: " (vec up))))))

(t/deftest wire-builders-are-leaf-entries
  (testing "a wire module is named by kmet.ai.wires alone — the dispatcher never grows a dispatch
            (wire-to-wire requires are composition: azure/vertex/codex reuse the
            responses and google helpers)"
    (doseq [f (source-files ["modules/ai/src"])
            :let [{:keys [ns requires]} (ns-info f)
                  wires (filter wire-require? requires)
                  allowed? (or (= ns 'kmet.ai.wires)
                               (str/starts-with? (str ns) "kmet.ai.api."))]
            :when (and (seq wires) (not allowed?))]
      (is false (str f " (" ns ") must not require wire builders " (vec wires)
                     " — only kmet.ai.wires (or another builder) names them")))))

(t/deftest dispatcher-names-no-wire
  (testing "kmet.ai.llm reaches the builders through kmet.ai.wires, nothing else"
    (let [{:keys [requires]} (ns-info "modules/ai/src/kmet/ai/llm.clj")
          direct (filter wire-require? requires)]
      (is (empty? direct)
          (str "kmet.ai.llm must require kmet.ai.wires, found direct wire deps: " (vec direct))))))

(t/deftest provider-event-hooks-are-options
  (testing "the hook slot namespace is gone"
    (doseq [f (source-files)
            :let [{:keys [ns requires]} (ns-info f)]
            :when (contains? (set requires) 'kmet.ai.hooks)]
      (is false (str f " (" ns ") requires kmet.ai.hooks; provider events are"
                     " send-message options — see kmet.ai.llm/:on-context"))))
  (testing "the slot helpers stay auth's, not a general registration back door"
    (doseq [f (source-files)
            :let [{:keys [ns requires]} (ns-info f)]
            :when (and (contains? (set requires) 'kmet.ai.slots)
                       (not= ns 'kmet.ai.auth))]
      (is false (str f " (" ns ") requires kmet.ai.slots; only kmet.ai.auth uses it")))))

(t/deftest http-decorator-is-a-pure-decorator
  (testing "kmet.ai.http takes its hooks as arguments and requires nothing from kmet.ai"
    (let [{:keys [requires]} (ns-info "modules/ai/src/kmet/ai/http.clj")
          deps (filter #(str/starts-with? (str %) "kmet.ai") requires)]
      (is (empty? deps)
          (str "kmet.ai.http must stay a decorator over kmet.libs.http, found: " (vec deps))))))
