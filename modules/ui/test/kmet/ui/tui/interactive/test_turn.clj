(ns kmet.ui.tui.interactive.test-turn
  "The turn path's shell handling: a `!` command runs with the agent's own
   `:shell` settings.

   Landing 9a made the shell settings a per-agent value and removed the process
   default `execute-bash` used to fall back on, so the TUI's `!` path — which
   calls the executor directly, outside any tool — has to pass them itself. A
   `future` conveys no dynamic bindings, which is why the test observes the
   executor's options rather than a `*shell*` binding."
  (:require [clojure.test :as t :refer [is testing]]
            [kmet.test-utils :as tu]
            [kmet.app.session.runtime :as runtime]
            [kmet.app.tools.bash-executor :as bash-exec]
            [kmet.app.tools.shell :as shell]
            [kmet.ui.tui.interactive.turn :as turn]
            [kmet.ui.tui.interactive.test-handle-support :as hs]))

(t/use-fixtures :each hs/with-boot tu/with-clean-app-registries)

(defn- await-until
  "Poll PRED every few ms until it holds or the deadline passes."
  [pred deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(t/deftest a-user-bash-command-runs-with-the-agents-shell
  (testing "`!` passes the agent's :shell settings to the executor — the
            process default that used to cover this path is gone"
    (let [calls (atom [])
          cs (hs/core-state (hs/new-handle))
          handle (:engine cs)]
      (shell/refresh! (runtime/agent-ctx handle)
                      {:shell-command-prefix "USER_SHELL=one"})
      (with-redefs [bash-exec/execute-bash
                    (fn [opts]
                      (swap! calls conj opts)
                      {:output "" :exit-code 0})]
        ((var turn/handle-bash-command) cs "echo hi" false)
        (is (await-until #(seq @calls) 3000) "the command reached the executor")
        (is (= "USER_SHELL=one" (:command-prefix (first @calls)))
            "with the agent's command prefix")
        (is (= "echo hi" (:command (first @calls))))))))

(t/deftest a-user-bash-command-without-shell-settings-passes-none
  (testing "an agent whose config names no shell has nothing to pass — the
            executor's platform default is the answer, not another agent's
            settings"
    (let [calls (atom [])
          cs (hs/core-state (hs/new-handle))]
      (shell/refresh! (runtime/agent-ctx (:engine cs)) {})
      (with-redefs [bash-exec/execute-bash
                    (fn [opts]
                      (swap! calls conj opts)
                      {:output "" :exit-code 0})]
        ((var turn/handle-bash-command) cs "echo hi" false)
        (is (await-until #(seq @calls) 3000))
        (is (nil? (:command-prefix (first @calls))))
        (is (nil? (:shell-path (first @calls))))))))
