(ns kmet.app.test-composition-run
  "The composition runs a turn — the gate 7a could not reach from a hand-mounted
   root: a pure kmet2 boot has no extension host until the `:extensions` row
   binds one, and the loop resolves its hooks through it.

   One turn, through the real rows: `create!` publishes an agent, `send!` wakes
   its run, the run reaches the (stubbed) model and settles `:completed`."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [kmet.agent.config :as cfg]
            [kmet.agent.event-bus :as event-bus]
            [kmet.ai.llm :as llm]
            [kmet.app.core :as core]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]))

(def ^:private sessions-dir "target/test-composition-run")

(deftest a-composed-agent-runs-a-turn
  (fs/create-dirs sessions-dir)
  (let [boot (runtime/boot (core/compose-config {:profile :tui}) {})]
    (try
      (let [agents (srce/get (:ctx boot) :agents)
            settled (promise)
            unsubscribe (event-bus/on-event :agent-settled
                                            (fn [ev] (deliver settled ev)))
            config {:provider :opencode-go
                    :model "deepseek-v4-flash"
                    :session-dir sessions-dir}]
        (try
          (with-redefs [cfg/get-api-key (fn [_] "test-key")
                        llm/send-message (fn [opts]
                                           (future
                                             (when-let [done (:on-done opts)]
                                               (done :stop))))]
            (let [handle ((:create! agents) (:ctx boot) {:config config})]
              ((:send! agents) handle "hello" {:target :next-turn})
              (is (= :completed (:outcome (deref settled 20000 ::timeout)))
                  "the booted composition runs a turn and settles it once")
              (is (= [:user :assistant]
                     (mapv :role @(:messages (:agent handle))))
                  "and the turn is the agent's context")))
          (finally (unsubscribe))))
      (finally (srce/dispose-subtree! (:ctx boot))
               (fs/delete-tree sessions-dir)))))
