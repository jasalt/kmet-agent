(ns kmet.agent.modes.config-command
  "The `kmet config` command's terminal UI (pi: cli/config-selector.ts — a
   standalone TuiMainScreen hosting the config selector). Command parsing
   and dispatch stay in kmet.agent.packages-cli (headless); this
   front-end namespace owns the terminal lifecycle and is injected there as
   :run-config! by the CLI composition root (kmet.agent.cli)."
  (:require [babashka.fs :as fs]
            [kmet.agent.config :as cfg]
            [kmet.agent.packages :as pkgs]
            [kmet.ui.tui.resource-config :as resource-config]
            [kmet.tui.core :as tui]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.terminal :as term]
            [kmet.tui.theme :as theme]))

(defn run-config!
  "Start the resource-configuration screen and block until it closes.
   Returns the process exit code (0). The starting write scope is
   project-local whenever a `.kmet/` project exists: kmet has no
   project-trust gate and project settings and auto dirs load
   unconditionally, so project-defined resources are shown enabled by
   default (pi starts global because its project mode needs trust). Without
   a `.kmet/` dir it starts global; `-l` forces project-local. The screen
   re-reads and writes the settings files directly. Escape/ctrl+c close with
   exit code 0."
  [{:keys [local]}]
  (let [config (cfg/init!)
        project-mode? (or local (fs/exists? (str (fs/path (fs/cwd) ".kmet"))))
        ;; custom themes for the config's :theme name (pi: initTheme after
        ;; the resource loader)
        _ (theme/load-theme-paths! (pkgs/resolve-theme-paths))
        terminal (term/create-terminal)
        tui (tui/create-tui terminal)
        ;; theme before the UI starts (pi: initTheme(settings.getTheme()))
        _ (theme/init-theme! (cfg/get-theme-name config))
        screen (resource-config/make-resource-config-screen
                :write-scope (if project-mode? :project :global)
                :project-mode? project-mode?
                :rows (try (max 10 (term/rows terminal)) (catch Exception _ 24))
                ;; The TUI publishes the live terminal size: Termux resizes
                ;; for the soft keyboard, and the screen re-lays out from
                ;; the size ref and forces a clean repaint on every height
                ;; change (a diff against the old-height frame leaves stale
                ;; rows and cursors on screen).
                :size-ref (tui/tui-terminal-size-ref tui)
                :request-render! (fn [] (tui/tui-request-render tui true))
                :on-close (fn [] (tui/tui-stop tui)))]
    (tui/tui-add-child tui screen)
    (tui/tui-set-focus tui screen)
    ;; the screen owns the terminal-size watch cancel; its dispose must run
    ;; even when tui-start throws, or the cancel is lost with the screen
    (try
      (tui/tui-start tui)
      (finally
        (tui/tui-stop tui)
        (protocols/dispose screen)))
    0))
