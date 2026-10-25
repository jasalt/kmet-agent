(ns kmet.agent.tools.core
  "Tool system public API — Tool record, registry, execution and the shared
   invocation pipeline. Re-exports from kmet.app.tools.tool,
   kmet.app.tools.registry and kmet.agent.tools.invoke.

   **kmet1's convenience arities live here, not in the registry.** The
   registry takes its `Registry` handle as the first argument, because kmet2
   gives each agent its own tool table; kmet1 predates that and has exactly one
   registry for the process, so its call sites leave the handle out and this
   namespace supplies `(kmet.agent.app-runtime/registry)` — the façade that
   routes each tag to the app's service. Explicit calls are re-exported
   untouched: this namespace adds arities, it never changes one, and it has no
   three-argument `register-tool!` for the same reason — a caller that holds a
   handle calls the registry, where the handle is first and an owner can never
   be mistaken for one.

   The split is what keeps the tool layer below `app`: the registry needs no
   process state, and the one process handle kmet1 has lives in a rank-4
   namespace, where needing it is allowed.

   The loop no longer comes here: it resolves its table and its dispatcher
   from the agent ctx's `:tools` service (`kmet.app.loop.state/tool-table`,
   `tool-executor`), which is 4c-iii-c's property — two agents, two tables. What
   keeps these arities alive is the TUI's display read
   (`kmet.ui.tui.chat-history` resolves a tool's render fn while building
   a message component, where no engine is in hand); that read moves with the
   TUI at landing 5, and the arities go with it."
  (:require [kmet.agent.app-runtime :as app-runtime]
            [kmet.app.tools.tool :as tool]
            [kmet.app.tools.registry :as registry]
            [kmet.app.tools.invoke :as invoke]))

(defn- app-registry
  "kmet1's handle, resolved per call rather than captured: the first call boots
   the app root, and a capture would freeze whatever existed at load time."
  []
  (app-runtime/registry))

;; ─── From tool.clj (Tool record + helpers) ──────────────────────────────────

(def map->Tool tool/map->Tool)
(def param tool/param)
(def ->json-schema tool/->json-schema)
(def make-tool tool/make-tool)

(def normalize-tool-definition tool/normalize-tool-definition)

;; ─── From registry.clj (registry + execution) ───────────────────────────────
;; The handle-taking fns get an arity that leaves it out; the rest are
;; re-exported as-is.

(defn get-all-tools
  ([] (registry/get-all-tools (app-registry)))
  ([reg] (registry/get-all-tools reg)))

(defn get-tool
  ([name] (registry/get-tool (app-registry) name))
  ([reg name] (registry/get-tool reg name)))

(defn register-tool!
  ([tool] (registry/register-tool! (app-registry) tool))
  ([tool owner] (registry/register-tool! (app-registry) tool owner)))

(defn unregister-tool!
  ([name] (registry/unregister-tool! (app-registry) name))
  ([name owner] (registry/unregister-tool! (app-registry) name owner)))

(defn register-tool-source!
  ([id tools-fn] (registry/register-tool-source! (app-registry) id tools-fn))
  ([id tools-fn owner]
   (registry/register-tool-source! (app-registry) id tools-fn owner)))

(defn unregister-tool-source!
  ([id] (registry/unregister-tool-source! (app-registry) id))
  ([id owner] (registry/unregister-tool-source! (app-registry) id owner)))

(defn get-contributed-tools
  ([] (registry/get-contributed-tools (app-registry)))
  ([reg] (registry/get-contributed-tools reg)))

(defn execute-tool
  ([tool-name args] (registry/execute-tool (app-registry) tool-name args))
  ([tool-name args opts] (registry/execute-tool (app-registry) tool-name args opts)))
(def select-tools registry/select-tools)
(def builtin-tool-names registry/builtin-tool-names)
(def resolve-default-tools registry/resolve-default-tools)
(def default-tool-exclusions registry/default-tool-exclusions)

;; ─── From invoke.clj (the shared invocation pipeline) ──────────────────────

(def prepare-tool-call invoke/prepare-tool-call)
(def execute-tool-call invoke/execute-tool-call)
(def finish-tool-call invoke/finish-tool-call)
