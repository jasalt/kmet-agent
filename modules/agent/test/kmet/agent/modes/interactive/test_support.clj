(ns kmet.agent.modes.interactive.test-support
  "Shared stubs for the interactive-mode tests: a dock stand-in, the
   command context, and the autocomplete provider normalizer."
  (:require [kmet.test-utils :as tu]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.hikap :as hiccup]
            [kmet.tui.protocols :as protocols]
            [kmet.agent.modes.interactive.ui-registry :as ui-registry]
            [kmet.agent.modes.interactive.turn :as turn]
            [kmet.agent.keybindings :as app-kb]
            [kmet.ui.tui.pending-messages :as pending-messages]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.agent.loop :as agent]
            [kmet.agent.session.core :as session]
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
  "A minimal CoreState-like map for the status swap helpers: the status
   layer is a hiccup/root-mounted fn component over a :status-current atom
   (dsl.md stage 4), so renders go through the root's reaction + reconcile.
   The editor atom holds no editor — a custom editor that cannot embed the
   status, so the standalone layer renders."
  []
  (let [si (status-indicator/make-status-indicator :text "Working")
        cur (atom nil)
        ced (atom nil)]
    (tu/with-test-engine
      {:tui {:render-requested? (atom false)}
       :status-indicator si
       :status-current cur
       :current-editor-atom ced
       :status-root (hiccup/root (status-indicator/make-status-area cur si ced))
       :running-turn? (atom true)})))

(defn status-lines [cs]
  (protocols/render (:status-root cs) 60))

(defn install-app-keybindings!
  "Install the app keybindings manager (the interactive mode does this at
   startup; tests drive overlay keys, which match app.models.* ids)."
  []
  (tui-kb/set-global-keybindings!
   (app-kb/create-agent-keybindings-manager "target/test-interactive-ui-kb")))

(defn compaction-cs
  "A CoreState-like map for compaction-queue tests, with a runtime engine
   sharing its session/turn/queue atoms."
  []
  (let [ag (agent/make-agent-state)
        ag-atom (atom ag)
        sess-atom (atom (session/create-session
                         (str "target/test-compaction-queue-" (System/currentTimeMillis))))
        engine (tu/test-engine {:agent-state ag-atom :session sess-atom})
        ch (chat-history/make-chat-history)
        ed (editor/make-editor)
        si (status-indicator/make-status-indicator :text "Working")
        cur (atom nil)
        ;; a plain editor (no top-border fn) cannot embed the status, so
        ;; the standalone layer renders for these tests
        ced (atom ed)]
    {:tui {:render-requested? (atom false)}
     :engine engine
     :agent-state ag-atom
     :chat-history ch
     :editor ed
     :current-editor-atom ced
     :compaction-queued (:compaction-queued engine)
     :running-turn? (:turn-running? engine)
     :bash-running? (atom false)
     :bash-signal (atom false)
     :status-indicator si
     :status-current cur
     :status-root (hiccup/root (status-indicator/make-status-area cur si ced))
     :footer-comp nil
     :footer-provider nil
     :pending-messages-comp (pending-messages/make-pending-messages)
     :session-atom sess-atom}))
