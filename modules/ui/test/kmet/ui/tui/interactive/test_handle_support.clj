(ns kmet.ui.tui.interactive.test-handle-support
  "A booted `:tui` composition and the handle its layout would create, for the
   tests that must prove the mode over the real registry and not a stand-in
   engine.

   `with-boot` is the per-test fixture for a namespace where every test needs
   the composition; `with-handle` boots one for a single test. Both delete the
   session tree they wrote and clear the process-wide extension slot — a
   composition left installed would leak into the next test.

   Boot publishes the composition's registry as the process command registry
   (its disposer restores the previous one), so a test registers its builtins
   *inside* `with-handle`; a registration made before the boot lands in the
   handle the boot replaces and `find-command` will not see it."
  (:require [babashka.fs :as fs]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.session.core :as session]
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
            [kmet.ui.tui.pending-messages :as pending-messages]
            [kmet.ui.tui.status-indicator :as status-indicator]))

(def ^:private sessions-dir "target/test-interactive-handle")

(def ^:private booted
  "The composition the `with-boot` fixture installed; nil outside it."
  (atom nil))

(defn start!
  "A fresh booted `:tui` composition — the real rows, so the handle
   `build-layout` would create is the one a front end reads."
  []
  (fs/create-dirs sessions-dir)
  (runtime-app/boot (core/compose-config {:profile :tui}) {}))

(defn stop!
  "Tear BOOT down and remove the sessions it wrote."
  [boot]
  (runtime-app/shutdown! boot)
  (fs/delete-tree sessions-dir)
  (extensions/clear-runtime!))

(defn with-boot
  "The fixture a whole namespace uses: one composition per test, torn down by
   the fixture so a test cannot forget it."
  [f]
  (let [boot (start!)]
    (reset! booted boot)
    (try
      (f)
      (finally
        (stop! boot)
        (reset! booted nil)))))

(defn current
  "The composition `with-boot` installed for the running test."
  []
  @booted)

(defn create-handle!
  "The agent the TUI's layout creates: through the composition's registry, with
   the run config a TUI run hands its factory (and the session the run
   resolved, created here when OPTS names none). OPTS merge over that config."
  ([boot] (create-handle! boot {}))
  ([{:keys [ctx]} {:keys [session] :as opts}]
   (let [agents (srce/get ctx :agents)
         session (or session (session/create-session sessions-dir))]
     {:ctx ctx
      :agents agents
      :session session
      :handle ((:create! agents)
               ctx
               {:session session
                :config (merge {:provider :opencode-go
                                :model "claude-haiku-5-5"
                                :session-dir sessions-dir}
                               (dissoc opts :session))})})))

(defn with-handle
  "Boot a composition for F and hand it the created handle map; the boot is
   torn down afterwards. A composition per test is the cost of reading the
   real registry, so only the tests that need one pay it."
  [f]
  (let [boot (start!)]
    (try
      (f (create-handle! boot))
      (finally
        (stop! boot)))))

(defn core-state
  "The CoreState pieces /new and the session commands touch, over the real
   handle: a chat history, the editor, the pending containers, a footer bound
   to the run's session, the status layer, and the bash flags they settle."
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
      :status-current (atom nil)
      :status-root nil
      :status-indicator (status-indicator/make-status-indicator)
      :footer-comp (footer/make-footer :provider fdp)
      :footer-provider fdp})))
