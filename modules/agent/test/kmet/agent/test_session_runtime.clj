(ns kmet.agent.test-session-runtime
  "Tests for kmet.agent.session.runtime: the engine front ends drive, and
   the front-end surface (state accessors + operations) modes and app UI
   use to read and drive it."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.agent.commands :as commands]
            [kmet.agent.config :as cfg]
            [kmet.agent.event-bus :as event-bus]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.loop :as agent]
            [kmet.agent.prompts :as prompts]
            [kmet.agent.runtime :as app]
            [kmet.agent.session.core :as session]
            [kmet.agent.skills :as skills]
            [kmet.agent.session.runtime :as rt]
            [kmet.agent.tools.invoke :as invoke]
            [kmet.agent.tools.registry :as tools-reg]
            [kmet.ai.models :as models]
            [kmet.libs.scope :as scope]
            [kmet.test-utils :as tu]))

;; Several tests bootstrap real engines onto the app tree (loading the
;; developer's own skills and prompts) and register app-wide fixtures; clear
;; the process-global registrations between tests.
(clojure.test/use-fixtures :each tu/with-clean-app-registries)

(defn- test-config
  "A sparse run config — every derived option falls back to its default."
  []
  {:provider :opencode-go :model "deepseek-v4-flash"})

(defn- with-engine
  "Bootstrap an engine over a fresh temp session and call F with it,
   disposing it and removing the temp dir afterwards."
  [f]
  (let [dir (str "target/test-session-runtime-" (System/currentTimeMillis))
        sess (session/create-session dir {:cwd (str (fs/cwd))})]
    (try
      (let [engine (rt/bootstrap (test-config) {:session sess})]
        (try (f engine sess)
             (finally (rt/dispose! engine))))
      (finally (fs/delete-tree dir)))))

(deftest test-commands-outlive-an-engine
  ;; The app root's store owns commands, so disposing an engine does not drop
  ;; them — with the engine atom they were its copy of the pending buffer.
  (with-engine
    (fn [_engine _sess]
      (commands/register-command! (app/node)
                                  {:name "outlives-demo" :handler (fn [& _] nil)})))
  (try
    (is (some? (commands/find-command "outlives-demo")))
    (finally
      (commands/unregister-command! (app/node) "outlives-demo"))))

(deftest test-tools-outlive-an-engine
  ;; Custom tools and contributed sources are the app's too (P2): registered
  ;; while an engine runs, they are found after its disposal, where the
  ;; pending buffer could only have filled the engine that was copied from.
  (with-engine
    (fn [_engine _sess]
      (tools-reg/register-tool!
       (app/node)
       {:name "outlives-tool" :label "Outlives" :description "d"
        :parameters {:type "object" :properties {}}
        :execute (fn [_] {:content "ok"})})
      (tools-reg/register-tool-source! (app/node) ::outlives
                                       (fn [] {"sandbox-outlives" {}}))))
  (try
    (is (contains? (tools-reg/get-all-tools) "outlives-tool"))
    (is (contains? (tools-reg/get-contributed-tools) "sandbox-outlives"))
    (finally
      (tools-reg/unregister-tool! (app/node) "outlives-tool")
      (tools-reg/unregister-tool-source! (app/node) ::outlives))))

(deftest test-prompt-templates-outlive-an-engine
  ;; Prompt templates are the app's too (P2): one registered while an engine
  ;; runs is found after its disposal, and looked up by name from the store.
  (with-engine
    (fn [_engine _sess]
      (prompts/register-prompt-template! (app/node)
                                         {:name "outlives-template" :content "---\ndescription: d\n---\nBody"
                                          :location "my-ext:prompts/outlives.md" :extension "my-ext"})))
  (try
    (is (= "Body" (:content (prompts/get-prompt-template "outlives-template"))))
    (finally
      (prompts/clear-prompt-templates! (app/node)))))

(deftest test-skills-outlive-an-engine
  ;; Skills are the app's too (P2): one registered while an engine runs is
  ;; found after its disposal, and looked up by name from the store.
  (with-engine
    (fn [_engine _sess]
      (skills/register-skill! (app/node) "outlives-skill" "A skill that outlives the engine.")))
  (try
    (is (= "outlives-skill" (:name (skills/get-skill "outlives-skill"))))
    (finally
      (skills/clear-skills! (app/node)))))

(deftest test-bootstrap-builds-the-engine
  (with-engine
    (fn [engine sess]
      (let [ag (rt/agent-state engine)]
        (is (identical? sess (rt/session engine)) "the session is the resolved record")
        (is (identical? sess (:session ag)) "the agent runs on the same session")
        (is (= :opencode-go @(:provider ag)))
        (is (= "deepseek-v4-flash" @(:model ag)))
        (is (string? @(:system ag)) "the system prompt was built")
        (is (fn? (:on-event ag)) "the agent's UI callback is the subscriber dispatcher")
        (is (= [] (rt/scoped-models engine)))
        (is (= :off @(:thinking ag)) "thinking defaults to off")
        (is (identical? sess (extensions/get-session))
            "the extension session handle is installed")
        (is (contains? (scope/owner-keys (:node ag) [:app :session]) :kmet/session)
            "the live session is registered under the tree's [:app :session]")
        (is (contains? (scope/owner-keys (:node ag) [:app :ui]) :kmet/ui-port)
            "the front end's headless port is registered under [:app :ui]")))))

(deftest test-subscribe-receives-and-unsubscribes
  (with-engine
    (fn [engine _]
      (let [events (atom [])
            unsub (rt/subscribe! engine #(swap! events conj %))]
        (rt/cancel! engine)
        (is (seq @events) "the subscriber receives the stream")
        (is (= :queue-update (:type (first @events))))
        (reset! events [])
        (unsub)
        (rt/cancel! engine)
        (is (empty? @events) "an unsubscribed fn receives nothing")))))

(deftest test-subscriber-errors-are-isolated
  (with-engine
    (fn [engine _]
      (let [events (atom [])]
        (rt/subscribe! engine (fn [_] (throw (ex-info "boom" {}))))
        (rt/subscribe! engine #(swap! events conj %))
        (is (nil? (rt/cancel! engine))
            "a throwing subscriber cannot escape into the loop")
        (is (seq @events) "the other subscribers still receive the event")))))

(deftest test-prompt-passes-through-and-records-the-run
  (with-engine
    (fn [engine _]
      (let [seen (atom nil)
            run (future :settled)
            called (atom [])
            done (fn [t] (swap! called conj t))]
        (with-redefs [agent/run-agent-turn
                      (fn [ag run-opts]
                        (reset! seen {:ag ag :opts run-opts})
                        run)]
          (is (nil? (rt/prompt! engine {:text "hi" :on-done done}))
              "prompt! returns immediately (completion is :agent-settled)")
          (is (identical? (rt/agent-state engine) (:ag @seen)))
          (is (= "hi" (:message (:opts @seen))))
          (is (true? (rt/turn-running? engine)) "the prompt owns the turn flag")
          (is (fn? (:on-done (:opts @seen))) "the runtime wraps the callback")
          ((:on-done (:opts @seen)) "answer")
          (is (= ["answer"] @called) "the front end's :on-done runs after the flag clears")
          (is (false? (rt/turn-running? engine))
              "the wrapped :on-done clears the turn flag before the front end runs")
          (is (identical? run @(:last-run engine)) "the run is recorded for dispose!"))))))

(deftest test-prompt-uses-the-live-agent-after-a-session-switch
  ;; a session switch replaces the agent state in the shared atom; prompt!
  ;; must run the new record, not the bootstrap one
  (with-engine
    (fn [engine _]
      (let [seen (atom nil)]
        (reset! (:agent-state engine)
                (assoc (rt/agent-state engine) :session ::switched))
        (with-redefs [agent/run-agent-turn (fn [ag _]
                                             (reset! seen (:session ag))
                                             (future))]
          (rt/prompt! engine {:text "hi"})
          (is (= ::switched @seen)))))))

(deftest test-dispose-clears-subscribers-and-wiring
  (with-engine
    (fn [engine _]
      (let [events (atom [])
            node (:node (rt/agent-state engine))]
        (rt/subscribe! engine #(swap! events conj %))
        (is (nil? (rt/dispose! engine)))
        (is (nil? (extensions/get-session)) "the extension session handle is cleared")
        (is (empty? (scope/owner-keys node [:app :session]))
            "dispose! drops the session surface's registrations with their owner")
        (is (empty? (scope/owner-keys node [:app :ui]))
            "dispose! drops the front-end surface's registrations")
        (is (nil? (rt/dispose! engine)) "dispose! is idempotent")
        (rt/cancel! engine)
        (is (empty? @events))))))

(deftest test-session-install-is-owner-scoped
  ;; The process cache is last-wins (one live front end at a time), but a
  ;; registration belongs to the tree that installed it: an older tree's
  ;; teardown cannot clear a newer tree's session (scope.md §2, §5).
  (let [old (app/node)
        newer (app/child-node)]
    (try
      (extensions/install-session! old :old-session)
      (extensions/install-session! newer :newer-session)
      (is (= :newer-session (extensions/get-session)))
      (scope/dispose-owner! old [:app :session])
      (is (= :newer-session (extensions/get-session))
          "the older tree's teardown cannot clear the newer install")
      (finally
        (scope/dispose-owner! newer [:app :session])))))

(deftest test-snapshot-is-plain-data
  (with-engine
    (fn [engine sess]
      (let [snap (rt/snapshot engine)]
        (is (identical? sess (:session snap)))
        (is (= :idle (:status snap)))
        (is (= :opencode-go (:provider snap)))
        (is (= "deepseek-v4-flash" (:model snap)))
        (is (= {:steering [] :follow-up []} (:queues snap)))
        (is (= :completed (:last-outcome snap)))
        (is (not (contains? snap :subscribers)) "the snapshot is a view, not the engine")))))

(deftest test-front-end-state-accessors
  (with-engine
    (fn [engine _]
      (let [ag (rt/agent-state engine)]
        (is (= "deepseek-v4-flash" (rt/model engine)))
        (is (= :opencode-go (rt/provider engine)))
        (with-redefs [models/get-model (fn [p id] {:provider p :id id})]
          (is (= {:provider :opencode-go :id "deepseek-v4-flash"}
                 (rt/model-record engine))
              "the record resolves from the live provider/model"))
        (is (= :off (rt/thinking-level engine)))
        (reset! (:thinking ag) :high)
        (is (= :high (rt/thinking-level engine))
            "accessors read the live atom, not a captured record")
        (is (= @(:system ag) (rt/system-prompt engine)))
        (is (false? (rt/compacting? engine)))
        (reset! (:compacting? ag) true)
        (is (true? (rt/compacting? engine)))
        (is (nil? (rt/compaction-error engine)))
        (reset! (:compaction-error ag) "boom")
        (is (= "boom" (rt/compaction-error engine)))
        (is (false? (rt/cancel-signal engine)))
        (reset! (:signal ag) true)
        (is (true? (rt/cancel-signal engine)))
        (is (false? (rt/has-queued-messages? engine)))
        (rt/steer! engine "queued")
        (is (true? (rt/has-queued-messages? engine))
            "a queued steer message makes the engine non-idle for extensions")))))

(deftest test-run-options-are-live-and-merge
  (with-engine
    (fn [engine _]
      (is (= :all (:steering-mode (rt/run-options engine))))
      (rt/set-run-options! engine {:context-window 12345})
      (is (= 12345 (:context-window (rt/run-options engine)))
          "the option applies live")
      (is (= :all (:steering-mode (rt/run-options engine)))
          "merge keeps untouched options")
      (rt/set-run-options! engine {:steering-mode :one-at-a-time})
      (is (= :one-at-a-time (:steering-mode (rt/run-options engine))))
      (is (= 12345 (:context-window (rt/run-options engine)))))))

(deftest test-snapshot-carries-queue-and-compaction-state
  (with-engine
    (fn [engine _]
      (rt/set-scoped-models! engine ["opencode-go/a" "opencode-go/b"])
      (rt/set-run-options! engine {:steering-mode :one-at-a-time
                                   :follow-up-mode :all
                                   :auto-compact false})
      (reset! (:compacting? (rt/agent-state engine)) true)
      (let [snap (rt/snapshot engine)]
        (is (= ["opencode-go/a" "opencode-go/b"] (:scoped-models snap)))
        (is (true? (:compacting? snap)))
        (is (= :one-at-a-time (:steering-mode snap)))
        (is (= :all (:follow-up-mode snap)))
        (is (false? (:auto-compact snap)))))))

(deftest test-set-system-prompt-replaces-prompt-and-opts
  (with-engine
    (fn [engine _]
      (let [ag (rt/agent-state engine)
            opts {:cwd "target/test-prompt-cwd"}]
        (rt/set-system-prompt! engine "custom prompt" opts)
        (is (= "custom prompt" (rt/system-prompt engine)))
        (is (= opts @(:system-prompt-opts ag)))))))

(deftest test-active-tools-follow-the-default-selection
  (with-engine
    (fn [engine _]
      (is (contains? (set (map :name (rt/active-tools engine))) "read")
          "the bootstrap default set is active")
      (is (= ["read" "bash"]
             (rt/apply-default-tools! engine ["read" "bash"]))
          "the applied selection is returned")
      (let [names (set (map :name (rt/active-tools engine)))]
        (is (contains? names "read"))
        (is (contains? names "bash"))
        (is (not (contains? names "write"))
            "built-ins outside the selection are inactive")))))

(deftest test-add-context-message-and-bash-result
  (with-engine
    (fn [engine _]
      (let [before (count (rt/messages engine))]
        (rt/add-context-message! engine {:role :custom :content "note"})
        (is (= (inc before) (count (rt/messages engine))))
        (is (= [{:type :text :text "note"}] (:content (last (rt/messages engine))))
            "string content is normalized to a text block"))
      (rt/add-bash-result! engine "echo hi" {:output "hi" :exit-code 0} false)
      (let [entry (last (rt/messages engine))]
        (is (= :bash (:role entry)) "an idle bash result reaches the context")
        (is (= "echo hi" (:command entry)))
        (is (= "hi" (:output entry)))))))

(deftest test-abort-compaction-signals
  (with-engine
    (fn [engine _]
      (let [ag (rt/agent-state engine)]
        (is (false? @(:compaction-signal ag)))
        (is (nil? (rt/abort-compaction! engine)))
        (is (true? @(:compaction-signal ag)) "escape during compaction signals it")
        (is (false? @(:signal ag)) "the run-cancel signal is untouched")))))

(deftest test-request-compaction-starts-when-idle
  (with-engine
    (fn [engine _]
      (let [completed (promise)]
        (with-redefs [agent/compact-context! (fn [& _] true)]
          (is (= :started (rt/request-compaction! engine nil {:on-complete #(deliver completed %)})))
          (is (true? (deref completed 2000 ::timeout))
              "an idle request starts immediately and reports its result"))
        (reset! (:compacting? (rt/agent-state engine)) true)
        (is (= :compacting (rt/request-compaction! engine nil nil))
            "a compaction in flight refuses a second one")
        (is (empty? (rt/pending-compaction engine))
            "a refused request is not queued")))))

(deftest test-request-compaction-defers-while-a-turn-runs
  (with-engine
    (fn [engine _]
      (let [completed (promise)
            seen (atom [])]
        (reset! (:turn-running? engine) true)
        (with-redefs [agent/compact-context!
                      (fn [_ag instructions reason]
                        (swap! seen conj [instructions reason])
                        true)]
          (is (= :scheduled
                 (rt/request-compaction! engine "focus" {:on-complete #(deliver completed %)}))
              "a live prompt defers the request")
          (is (= ["focus"] (mapv :custom-instructions (rt/pending-compaction engine))))
          ;; a settle while a newer prompt already owns the runtime leaves it
          ;; queued for the next settle
          ((:on-event (rt/agent-state engine)) {:type :agent-settled :outcome :completed})
          (is (false? (realized? completed)) "a busy settle does not start it")
          (is (= 1 (count (rt/pending-compaction engine))) "still queued")
          (reset! (:turn-running? engine) false)
          ((:on-event (rt/agent-state engine)) {:type :agent-settled :outcome :completed})
          (is (true? (deref completed 2000 ::timeout))
              "the settle event starts the deferred compaction")
          (is (= [["focus" :manual]] @seen)
              "it runs as a manual compaction with its instructions"))
        (is (empty? (rt/pending-compaction engine)) "the queue drained")))))

(deftest test-request-compaction-reports-failures
  (with-engine
    (fn [engine _]
      (let [failed (promise)]
        (with-redefs [agent/compact-context!
                      (fn [ag & _]
                        (reset! (:compaction-error ag) "Compaction failed: boom")
                        :failed)]
          (rt/request-compaction! engine nil {:on-error #(deliver failed (ex-message %))})
          (is (= "Compaction failed: boom" (deref failed 2000 ::timeout))
              ":failed reports pi's message through :on-error"))))))

(deftest test-new-session-drops-a-deferred-compaction
  (testing "a compaction deferred for the outgoing session dies with it —
            starting a new session must not let the settle drain run it
            against the swap"
    (let [dir (str "target/test-session-runtime-new-" (System/currentTimeMillis))
          sess (session/create-session dir {:cwd (str (fs/cwd))})]
      (try
        (let [engine (rt/bootstrap (assoc (test-config) :session-dir dir) {:session sess})]
          (try
            (reset! (:turn-running? engine) true)
            (is (= :scheduled (rt/request-compaction! engine nil nil)))
            (is (= 1 (count (rt/pending-compaction engine))))
            (rt/new-session! engine)
            (is (empty? (rt/pending-compaction engine))
                "the deferred request is dropped before the settle")
            (finally (rt/dispose! engine))))
        (finally (fs/delete-tree dir))))))

(deftest test-http-timeout-setters
  (with-engine
    (fn [engine _]
      (rt/set-http-idle-timeout-ms! engine 5000)
      (is (= 5000 (:http-idle-timeout-ms (rt/run-options engine))))
      (rt/set-http-total-timeout-ms! engine 9000)
      (is (= 9000 (:http-total-timeout-ms (rt/run-options engine))))
      (rt/set-http-total-timeout-ms! engine nil)
      (is (nil? (:http-total-timeout-ms (rt/run-options engine)))
          "nil follows the idle timeout"))))

(deftest test-switch-model-applies-the-rank-rule-and-persists
  (with-engine
    (fn [engine _]
      (let [ag (rt/agent-state engine)
            reasoner (models/map->Model {:provider :test-native :id "reasoner"
                                         :reasoning true})
            plain (models/map->Model {:provider :test-native :id "plain"
                                      :reasoning false})
            persisted (atom ::none)]
        (with-redefs [models/get-model (fn [p id]
                                         (when (= p :test-native)
                                           (case id
                                             "reasoner" reasoner
                                             "plain" plain
                                             nil)))
                      cfg/set-default-model! (fn [p id] (reset! persisted [p id]))]
          (reset! (:provider ag) :test-native)
          (reset! (:model ag) "reasoner")
          (reset! (:thinking ag) :low)
          (testing "a non-reasoning target drops the reasoning level to :off"
            (is (= :off (rt/switch-model! engine plain nil)))
            (is (= "plain" (rt/model engine)) "the model is applied")
            (is (= :off (rt/thinking-level engine)) "the clamped level is applied")
            (is (= [:test-native "plain"] @persisted) "persisted as the default"))
          (testing "an explicit level wins and is clamped to the target"
            (is (= :high (rt/switch-model! engine reasoner :high)))
            (is (= :high (rt/thinking-level engine)))
            (is (= [:test-native "reasoner"] @persisted))))))))

(deftest test-generate-branch-summary-forwards-to-the-agent
  (with-engine
    (fn [engine _]
      (let [signal (atom false)
            seen (atom nil)]
        (with-redefs [agent/generate-branch-summary
                      (fn [ag entries instructions sig replace?]
                        (reset! seen [ag entries instructions sig replace?])
                        {:summary "s"})]
          (is (= {:summary "s"}
                 (rt/generate-branch-summary engine [{:id "e1"}] "instr" signal true)))
          (is (identical? (rt/agent-state engine) (nth @seen 0))
              "the wrapper hands the live agent state over")
          (is (= [[{:id "e1"}] "instr" signal true] (subvec @seen 1))))))))

(deftest test-session-directory-helpers
  (let [dir (str "target/test-session-runtime-dirs-" (System/currentTimeMillis))
        cwd (str (fs/cwd))]
    (try
      (let [sess (session/create-session (rt/ensure-cwd-session-dir dir cwd)
                                         {:cwd cwd})]
        (is (= (some-> (session/session-dir-for-cwd dir cwd) fs/canonicalize str)
               (str (fs/parent (:file sess))))
            "a new session lands in the cwd-encoded dir under base-dir")
        (is (nil? (rt/find-recent-session dir cwd))
            "an empty cwd dir has no session to continue")
        (session/append-entry sess {:role :assistant :content "prior"})
        (is (= (str (fs/canonicalize (:file sess)))
               (str (fs/canonicalize (rt/find-recent-session dir cwd))))
            "the most recent session for the cwd is found"))
      (finally (fs/delete-tree dir)))))

(deftest test-resolve-run-session-policy
  (let [dir (str "target/test-session-runtime-resolve-" (System/currentTimeMillis))
        config {:provider :opencode-go :model "deepseek-v4-flash" :session-dir dir}]
    (try
      (let [fresh (rt/resolve-run-session config {})]
        (is (some? (:file fresh)) "a fresh session is created in the cwd dir")
        ;; sessions reach disk on their first entry (create-session is lazy),
        ;; and --continue/--session search the files
        (session/append-entry fresh {:role :assistant :content "prior"})
        (is (= (:id fresh) (:id (rt/resolve-run-session config {:continue true})))
            "--continue finds the most recent session for the cwd")
        (is (= (:id fresh)
               (:id (rt/resolve-run-session config {:session (subs (:id fresh) 0 8)})))
            "--session takes an id prefix"))
      (is (nil? (rt/resolve-run-session config {:session "no-such-session"}))
          "--session matching nothing resolves to nil (the caller reports it)")
      (finally (fs/delete-tree dir)))))

(deftest test-wait-idle
  (with-engine
    (fn [engine _]
      (is (true? (rt/wait-idle! engine 100)) "an idle engine is idle immediately")
      (reset! (:turn-running? engine) true)
      (is (false? (rt/wait-idle! engine 30)) "a stuck turn times out")
      (reset! (:turn-running? engine) false)
      (reset! (:compacting? (rt/agent-state engine)) true)
      (is (false? (rt/wait-idle! engine 30)) "a stuck compaction times out")
      (reset! (:compacting? (rt/agent-state engine)) false)
      (is (true? (rt/wait-idle! engine 100)) "idle again after both clear"))))

(deftest test-a-subscription-outlives-any-engine
  ;; Extensions subscribe at load, before any engine exists, and the bus is a
  ;; process-level registry (F10): an engine neither adopts nor wipes it, so
  ;; a subscription is live before the first engine and after that engine is
  ;; disposed — the engine-owned copy started the next one blind.
  (event-bus/clear-event-listeners!)
  (let [fired (atom 0)
        dereg (event-bus/on-event :message-start (fn [_] (swap! fired inc)))]
    (try
      (with-engine (fn [_ _] (event-bus/emit-event! {:type :message-start})))
      (is (= 1 @fired) "the subscription registered before the engine fires")
      (with-engine (fn [_ _] (event-bus/emit-event! {:type :message-start})))
      (is (= 2 @fired) "and it survives the first engine's disposal")
      (finally (dereg)))))

(deftest test-two-engines-do-not-cross-contaminate
  (let [dir1 (str "target/test-multi-engine-a-" (System/currentTimeMillis))
        dir2 (str "target/test-multi-engine-b-" (System/currentTimeMillis))
        sess1 (session/create-session
               (rt/ensure-cwd-session-dir dir1 (str (fs/cwd)))
               {:cwd (str (fs/cwd))})
        e1 (rt/bootstrap (test-config) {:session sess1})
        sess2 (session/create-session
               (rt/ensure-cwd-session-dir dir2 (str (fs/cwd)))
               {:cwd (str (fs/cwd))})
        e2 (rt/bootstrap (test-config) {:session sess2})]
    (try
      ;; The bus is not an engine's (F10): its registry is process-level, so
      ;; there is no per-engine listener storage to compare (see
      ;; test-a-subscription-outlives-any-engine).
      ;; The registries moved in P2 — tools, commands, prompts, skills,
      ;; renderers, flags and the extension hooks — are the app's, not an
      ;; engine's: neither engine owns them, so a registration reaches both,
      ;; and a deregistered hook is gone from the tree every engine reads.
      ;; The pending buffer could only fill the first engine, and its stale
      ;; copy outlived an unload.
      (let [fired (atom 0)
            dereg (extensions/register-input-hook! (app/node)
                                                   (extensions/extension-owner "test-hooks-app-wide")
                                                   (fn [_] (swap! fired inc) nil))]
        (try
          (commands/register-command! (app/node)
                                      {:name "app-wide-demo" :handler (fn [& _] nil)})
          (is (some? (commands/find-command "app-wide-demo")))
          (extensions/apply-input-hooks "hello" :interactive)
          (is (= 1 @fired) "the hook fires from the app tree alone")
          (dereg)
          (extensions/apply-input-hooks "hello" :interactive)
          (is (= 1 @fired) "and a deregistered hook stays gone")
          (finally
            (commands/unregister-command! (app/node) "app-wide-demo"))))
      (let [e1-events (atom [])
            e2-events (atom [])]
        (rt/subscribe! e1 #(swap! e1-events conj %))
        (rt/subscribe! e2 #(swap! e2-events conj %))
        (rt/cancel! e1)
        (is (seq @e1-events) "the first engine's subscribers receive its events")
        (is (empty? @e2-events) "the second engine's subscribers receive nothing"))
      (finally
        (rt/dispose! e2)
        (rt/dispose! e1)
        (fs/delete-tree dir1)
        (fs/delete-tree dir2)))))

(deftest test-tool-hooks-come-from-the-app-tree
  ;; The transform chain reads the store (P2), so a hook an extension
  ;; registered reaches the engine — and leaves when it is deregistered.
  ;; test-extensions covers the load/unload wiring end to end.
  (with-engine
    (fn [engine _sess]
      (let [ag (rt/agent-state engine)
            before (fn [ctx] (@(:before-tool-call ag) ctx))
            after (fn [ctx] (@(:after-tool-call ag) ctx))
            owner (extensions/extension-owner "test-tool-hooks")
            hook-call (fn [{:keys [tool-name args]}]
                        (if (= "kmet-test-danger" tool-name)
                          {:block true :reason "not that one" :terminate true}
                          {:args (assoc args :patched true)}))
            hook-result (fn [{:keys [result]}]
                          {:content (str "wrapped:" (:content result))
                           :is-error false})
            dereg-call (extensions/register-tool-call-hook! (app/node) owner hook-call)
            dereg-result (extensions/register-tool-result-hook! (app/node) owner hook-result)]
        (try
          (is (= {:block true :reason "not that one" :terminate true}
                 (before {:tool-name "kmet-test-danger" :args {}}))
              "a tool-call hook can block a call and says why")
          (is (= {:block {:content "not that one" :is-error true :terminate true}}
                 (invoke/prepare-tool-call
                  {:before-hook @(:before-tool-call ag)
                   :tool-name "kmet-test-danger"
                   :args {}}))
              "its :terminate reaches the call's block result (extension.md:916)")
          (is (true? (:patched (:args (before {:tool-name "read" :args {}}))))
              "or rewrite its arguments")
          (is (= "wrapped:ok"
                 (:content (after {:tool-name "read"
                                   :result {:content "ok" :is-error false}})))
              "a tool-result hook rewrites the result")
          (finally (dereg-call) (dereg-result)))
        (is (not-any? #(identical? hook-call %)
                      (extensions/get-tool-call-hooks))
            "a deregistered tool-call hook leaves the registry")
        (is (not-any? #(identical? hook-result %)
                      (extensions/get-tool-result-hooks))
            "so does a tool-result hook")))))
