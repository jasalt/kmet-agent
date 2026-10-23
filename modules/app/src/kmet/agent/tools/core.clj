(ns kmet.agent.tools.core
  "Tool system public API — Tool record, registry, execution and the shared
   invocation pipeline. Re-exports from kmet.agent.tools.tool,
   kmet.agent.tools.registry and kmet.agent.tools.invoke."
  (:require [kmet.agent.tools.tool :as tool]
            [kmet.agent.tools.registry :as registry]
            [kmet.agent.tools.invoke :as invoke]))

;; ─── From tool.clj (Tool record + helpers) ──────────────────────────────────

(def map->Tool tool/map->Tool)
(def param tool/param)
(def ->json-schema tool/->json-schema)
(def make-tool tool/make-tool)

(def normalize-tool-definition tool/normalize-tool-definition)

;; ─── From registry.clj (registry + execution) ───────────────────────────────

(def get-all-tools registry/get-all-tools)
(def execute-tool registry/execute-tool)
(def register-tool! registry/register-tool!)
(def unregister-tool! registry/unregister-tool!)
(def get-tool registry/get-tool)
(def register-tool-source! registry/register-tool-source!)
(def unregister-tool-source! registry/unregister-tool-source!)
(def get-contributed-tools registry/get-contributed-tools)
(def select-tools registry/select-tools)
(def builtin-tool-names registry/builtin-tool-names)
(def resolve-default-tools registry/resolve-default-tools)
(def default-tool-exclusions registry/default-tool-exclusions)

;; ─── From invoke.clj (the shared invocation pipeline) ──────────────────────

(def prepare-tool-call invoke/prepare-tool-call)
(def execute-tool-call invoke/execute-tool-call)
(def finish-tool-call invoke/finish-tool-call)
