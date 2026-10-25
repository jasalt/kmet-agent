(ns kmet.ui.tui.interactive.test-support
  "Shared stubs for the interactive-mode tests: a dock stand-in, the
   command context, and the autocomplete provider normalizer."
  (:require
   [kmet.tui.components.editor :as editor]
   [kmet.tui.hikap :as hiccup]
   [kmet.tui.protocols :as protocols]
   [kmet.ui.tui.interactive.test-handle-support :as hs]
   [kmet.ui.tui.interactive.ui-registry :as ui-registry]
   [kmet.ui.tui.interactive.turn :as turn]
   [kmet.agent.keybindings :as app-kb]
   [kmet.ui.tui.status-indicator :as status-indicator]
   [kmet.agent.tools.core :as tools]
   [kmet.ui.tui.chat-history :as chat-history]
   [kmet.tui.keybindings :as tui-kb]))

(defn capture-mount!
  "A dock/mount! stand-in for tests: records the component that receives
  keys (the :focus-target when given, else COMPONENT) in REF (pi:
  showSelector mounts into the editor dock and focuses the interactive
  child; the tests don't have a dock)."
  [ref]
  (fn [_ component & [opts]]
    (reset! ref (or (:focus-target opts) component))))

(defn command-ctx
  "The command context for CS: the engine-portable builtins take it directly
   (the interactive registry unwraps its own commands from :state)."
  [cs]
  {:runtime (:engine cs)
   :ui (turn/interactive-command-ui cs)
   :frontend :interactive})

(defn normalize [x]
  ((var ui-registry/normalize-autocomplete-provider) x))

(defn test-status-cs
  "A CoreState for the status swap helpers over a real handle: the status
   layer is a hiccup/root-mounted fn component over a :status-current atom
   (dsl.md stage 4), so renders go through the root's reaction + reconcile.
   The editor atom holds no editor — a custom editor that cannot embed the
   status, so the standalone layer renders.

   Requires the caller's `hs/with-boot` fixture (the handle comes from the
   installed composition)."
  []
  (let [si (status-indicator/make-status-indicator :text "Working")
        cur (atom nil)
        ced (atom nil)]
    (hs/core-state (hs/new-handle)
                   {:tui {:render-requested? (atom false)}
                    :status-indicator si
                    :status-current cur
                    :current-editor-atom ced
                    :status-root (hiccup/root (status-indicator/make-status-area cur si ced))
                    :turn-running? true})))

(defn status-lines [cs]
  (protocols/render (:status-root cs) 60))

(defn install-app-keybindings!
  "Install the app keybindings manager (the interactive mode does this at
   startup; tests drive overlay keys, which match app.models.* ids)."
  []
  (tui-kb/set-global-keybindings!
   (app-kb/create-agent-keybindings-manager "target/test-interactive-ui-kb")))

(defn compaction-cs
  "A CoreState for compaction-queue tests over a real handle, with the chat
   history that renders tool rows (the queue tests drop entries into it).
   Requires the caller's `hs/with-boot` fixture."
  []
  (let [handle (hs/new-handle)
        ed (editor/make-editor)
        si (status-indicator/make-status-indicator :text "Working")
        cur (atom nil)
        ;; a plain editor (no top-border fn) cannot embed the status, so
        ;; the standalone layer renders for these tests
        ced (atom ed)]
    (hs/core-state handle
                   {:tui {:render-requested? (atom false)}
                    :chat-history (chat-history/make-chat-history :tool-fn tools/get-tool)
                    :editor ed
                    :current-editor-atom ced
                    :status-indicator si
                    :status-current cur
                    :status-root (hiccup/root (status-indicator/make-status-area cur si ced))})))
