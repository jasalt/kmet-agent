(ns kmet.app.ui.suspend
  "Suspend-to-background flow (app.suspend, ctrl+z; pi:
   modes/interactive/interactive-mode.ts handleCtrlZ). The TUI hands the
   terminal back before the process group stops and reclaims it when the
   shell resumes us — the tui-suspend!/tui-resume! pair the external-editor
   flow already uses."
  (:require [kmet.app.ui.chat-history :as chat-history]
            [kmet.libs.process :as process]
            [kmet.tui.core :as tui]))

(defn handle-suspend
  "Stop kmet in the background (SIGTSTP), restarting the TUI when the shell
   brings it back with fg/bg (SIGCONT). Windows has no job-control stop, so
   it only reports that (pi: handleCtrlZ)."
  [cs]
  (if process/windows-os?
    (chat-history/chat-history-show-status!
     (:chat-history cs)
     "Suspend to background is not supported on Windows")
    (try
      (tui/tui-suspend! (:tui cs))
      (process/suspend-to-background!)
      (finally
        (tui/tui-resume! (:tui cs))))))
