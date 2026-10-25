(ns kmet.app.session.summary
  "The one-shot LLM work a front end runs *about* a session rather than inside
   a turn: the branch summary `/tree` shows after a navigation.

   It sits below the driver and the handle. `kmet.app.compaction.stage` owns
   the call, and this namespace is the name a front end may require — the layer
   guard forbids a mode reaching the stage directly, and the driver that used to
   be the only caller goes with kmet1's entry at retirement."
  (:require [kmet.app.compaction.stage :as agent-compact]
            [kmet.app.session.runtime :as runtime]))

(defn generate-branch-summary
  "LLM summary of abandoned branch entries for tree navigation, over ENGINE's
   agent state. Returns `{:summary str :usage map}` | `{:aborted true}` |
   `{:error msg}`; SIGNAL aborts the call."
  [engine entries custom-instructions signal replace-instructions?]
  (agent-compact/generate-branch-summary (runtime/agent-state engine) entries
                                         custom-instructions signal replace-instructions?))
