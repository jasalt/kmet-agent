(ns kmet.agent.modes.test-tui
  "The TUI mode row (`kmet.agent.modes.tui`): the plugin the `:tui` profile
   mounts, and the two properties a test can hold without a terminal — the row
   is named what the profile expects, and a diagnostic mount (no `:on-result`)
   starts nothing.

   The mode itself is kmet1's interactive entry (`kmet.agent.modes.interactive`)
   over the composition's ctx; running it needs a real terminal, which is what
   `scripts/capture/pty_capture.py` drives."
  (:require [clojure.test :refer [deftest is]]
            [kmet.agent.modes.tui :as tui]
            [kmet.srce.core :as srce]))

(deftest the-row-is-the-tui-mode
  (is (= :mode-tui (:name tui/plugin))
      "the name is what a failed mount reports and what --dump-state shows"))

(deftest a-diagnostic-mount-starts-nothing
  ;; `--dump-state` mounts every row and hands none of them an :on-result: a
  ;; diagnostic that started a terminal session would be the bug it is meant to
  ;; find.
  (let [root (srce/root {:name :app})]
    (try
      (is (nil? ((:apply tui/plugin) root {}))
          "no :on-result, so no run")
      (finally (srce/dispose-subtree! root)))))
