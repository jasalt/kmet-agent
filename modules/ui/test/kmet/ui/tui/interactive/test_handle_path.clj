(ns kmet.ui.tui.interactive.test-handle-path
  "The interactive mode's own code paths over the composition's handle — the
   re-host landing 8 finished, proven where the TUI runs it rather than against
   a stand-in engine.

   `/new` is the case that shows the seam: the handle's `:new-session!` moves
   the agent in place and installs nothing, so the mode's own
   `install-live-session!` is what points the process-wide extension slot at
   the session an out-of-run handler must see. A test that stood up kmet1's
   `session.driver` engine would pass even if the handle's op were missing, so
   this one creates through `:agents` and reads the registry afterwards: one
   agent, one new session."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.session.runtime :as runtime]
            [kmet.app.core :as core]
            [kmet.app.runtime :as runtime-app]
            [kmet.srce.core :as srce]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.editor :as editor]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.footer :as footer]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.ui.tui.interactive :as inter]
            [kmet.ui.tui.interactive.session-admin :as session-admin]
            [kmet.ui.tui.pending-messages :as pending-messages]))

(def ^:private sessions-dir "target/test-interactive-handle-path")

(def ^:private booted
  "The composition the current test runs against; the fixture owns it."
  (atom nil))

(defn- with-boot
  "Each test gets a fresh booted `:tui` composition — the real rows, so the
   handle `build-layout` would create is the one a front end reads — and the
   fixture is the teardown a test cannot forget."
  [f]
  (fs/create-dirs sessions-dir)
  (let [boot (runtime-app/boot (core/compose-config {:profile :tui}) {})]
    (reset! booted boot)
    (try
      (f)
      (finally
        (runtime-app/shutdown! boot)
        (fs/delete-tree sessions-dir)
        (extensions/clear-runtime!)
        (reset! booted nil)))))

(t/use-fixtures :each with-boot)

(defn- create-handle!
  "The agent the TUI's layout creates: through the composition's registry, with
   the run config a TUI run hands its factory."
  []
  (let [ctx (:ctx @booted)
        agents (srce/get ctx :agents)]
    {:ctx ctx
     :agents agents
     :handle ((:create! agents) ctx
                                {:config {:provider :opencode-go
                                          :model "claude-haiku-5-5"
                                          :session-dir sessions-dir}})}))

(defn- core-state
  "The CoreState pieces /new touches, over the real handle: a chat history, the
   editor, the pending containers, a footer bound to the run's session, and the
   bash flags it settles."
  [handle]
  (let [ch (chat-history/make-chat-history :tool-fn (constantly nil))
        ed (editor/make-editor)
        fdp (fdp/make-footer-data-provider :session (runtime/session handle))]
    (editor/editor-set-text! ed "draft")
    (inter/map->CoreState
     {:tui {:render-requested? (atom false)}
      :engine handle
      :chat-history ch
      :editor ed
      :current-editor-atom (atom ed)
      :anim-timer (atom nil)
      :bash-running? (atom false)
      :bash-signal (atom false)
      :pending-messages-container (container/make-container)
      :pending-bash-components (atom [])
      :pending-messages-comp (pending-messages/make-pending-messages)
      :footer-comp (footer/make-footer :provider fdp)
      :footer-provider fdp})))

(deftest new-session-moves-the-handles-session-not-an-agent
  (testing "the TUI's /new over a real handle: the agent stays, the session
            moves, the registry still lists one agent, and the front end's
            install is what an out-of-run extension handler sees"
    (let [{:keys [ctx agents handle]} (create-handle!)
          old-session (runtime/session handle)
          _ (event-bus/clear-event-listeners!)
          shutdown-events (atom [])
          _ (event-bus/on-event :session-shutdown
                                (fn [ev] (swap! shutdown-events conj ev)))
          cs (core-state handle)]
      (try
        (session-admin/handle-new-session cs)
        (let [new-session (runtime/session handle)]
          (is (not= (:id old-session) (:id new-session))
              "a fresh session is created")
          (is (= new-session (extensions/get-session))
              "the process slot names the session the user just switched to: an
               extension command runs outside a run and has no other answer")
          (is (= [(:id handle)] (mapv :id ((:list agents) ctx)))
              "the registry still lists the agent it listed, not a second one")
          (is (= [{:type :session-shutdown :reason :new
                   :target-session-file (:file old-session)}]
                 @shutdown-events)
              "extensions are told the runtime is torn down before the swap")
          (is (str/blank? (editor/editor-get-text (:editor cs)))
              "the editor is cleared"))
        (finally (event-bus/clear-event-listeners!))))))
