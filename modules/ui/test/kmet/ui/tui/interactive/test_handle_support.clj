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

(def ^:private booted
  "The composition the `with-boot` fixture installed; nil outside it."
  (atom nil))

(defn start!
  "A fresh booted `:tui` composition — the real rows, so the handle
   `build-layout` would create is the one a front end reads — with its own
   session directory, so a test that boots twice does not have the first
   boot's sessions deleted with the second's."
  []
  (let [dir (str "target/test-handle-" (System/nanoTime))]
    (fs/create-dirs dir)
    (assoc (runtime-app/boot (core/compose-config {:profile :tui}) {})
           ::sessions-dir dir)))

(defn stop!
  "Tear BOOT down and remove the sessions it wrote."
  [boot]
  (runtime-app/shutdown! boot)
  (fs/delete-tree (::sessions-dir boot) {:missing-ok true})
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
   resolved, created here when OPTS names none).

   OPTS: `:session`, `:cwd` (the runtime cwd the run starts at), `:config` — a
   map merged over the run config, the way a test names settings — and any
   further keys merged into it, so a test names one setting without rebuilding
   the map."
  ([boot] (create-handle! boot {}))
  ([{:keys [ctx] :as boot} {:keys [session config cwd] :as opts}]
   (let [dir (or (::sessions-dir boot)
                 (throw (ex-info "create-handle! takes a boot from start!"
                                 {:type ::not-a-boot})))
         agents (srce/get ctx :agents)
         sess (or session (session/create-session dir))
         handle ((:create! agents)
                 ctx
                 {:session sess
                  :config (merge {:provider :opencode-go
                                  :model "claude-haiku-5-5"
                                  :session-dir dir}
                                 config
                                 (dissoc opts :session :config :cwd))})]
     (when (some? cwd)
       (reset! (:cwd handle) cwd))
     {:ctx ctx
      :agents agents
      :handle handle})))

(defn new-handle
  "A handle over the composition `with-boot` installed — the fixture's, so a
   test that needs a CoreState does not boot a second composition."
  []
  (:handle (create-handle! (current))))

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

(defn- opt-or
  "OPTS' K, or DEFAULT when the caller did not name it — an explicit nil is
   none (the settings panel's :tui nil is a missing terminal, not a stub)."
  [opts k default]
  (if (contains? opts k) (get opts k) default))

(defn core-state
  "The CoreState pieces /new and the session commands touch, over a real
   handle: a chat history, the editor, the pending containers, a footer bound
   to the run's session, the status layer, and the bash flags they settle.

   OPTS override what it builds (`:tui`, `:chat-history`, `:editor`,
   `:current-editor-atom`, `:anim-timer`, `:bash-running?`, `:bash-signal`,
   `:pending-bash-components`, `:status-indicator`, `:status-current`,
   `:status-root`, `:footer-provider`, `:footer-comp`) and seed the handle's
   own atoms (`:session`, `:config`, `:cwd`, `:turn-running?`, `:messages`, `:model`,
   `:provider`, `:thinking`, `:scoped-models`, `:cfg`,
   `:compaction-queued`, `:pending-compaction`) — the values a test writes
   directly, written where the run reads them."
  ([handle] (core-state handle nil))
  ([handle opts]
   (doseq [k [:config :cwd :turn-running? :compaction-queued :pending-compaction]
           :when (contains? opts k)]
     (reset! (get handle k) (get opts k)))
   ;; the live session the handle answers is the state record's, the way a
   ;; switch replaces it (the `:session` thunk reads the record)
   (when (contains? opts :session)
     (reset! (:agent-state handle)
             (assoc @(:agent-state handle) :session (:session opts))))
   (let [state @(:agent-state handle)]
     (doseq [k [:messages :model :provider :thinking :scoped-models :cfg
                :compacting?]
             :when (contains? opts k)]
       (reset! (get state k) (get opts k))))
   (let [ch (opt-or opts :chat-history
                    (chat-history/make-chat-history :tool-fn (constantly nil)))
         ed (or (:editor opts) (editor/make-editor))
         fdp (opt-or opts :footer-provider
                     (fdp/make-footer-data-provider :session (runtime/session handle)))]
     (when (nil? (:editor opts))
       (editor/editor-set-text! ed "draft"))
     (inter/map->CoreState
      {:tui (opt-or opts :tui {:render-requested? (atom false)})
       :engine handle
       :chat-history ch
       :editor ed
       :current-editor-atom (opt-or opts :current-editor-atom (atom ed))
       :anim-timer (opt-or opts :anim-timer (atom nil))
       :bash-running? (opt-or opts :bash-running? (atom false))
       :bash-signal (opt-or opts :bash-signal (atom false))
       :pending-messages-container (container/make-container)
       :pending-bash-components (opt-or opts :pending-bash-components (atom []))
       :pending-messages-comp (pending-messages/make-pending-messages)
       :status-current (opt-or opts :status-current (atom nil))
       :status-root (:status-root opts)
       :status-indicator (opt-or opts :status-indicator
                                 (status-indicator/make-status-indicator))
       :footer-comp (opt-or opts :footer-comp (footer/make-footer :provider fdp))
       :footer-provider fdp}))))
