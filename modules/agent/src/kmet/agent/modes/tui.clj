(ns kmet.agent.modes.tui
  "The TUI as a mode row: `kmet --app new --profile tui`, the kmet2 entry's
   default profile.

   The row mounts kmet1's interactive mode over the composition the profile
   booted. Two things make that work without moving anything yet: the mode's
   engine takes its ctx as an argument (`kmet.agent.modes.interactive/run`), so
   the run reads the composition's own `:tools`, `:sessions` and
   `:systemPrompt` rows; and the startup a *process* world needs — catalogs,
   config, extension packages, themes — is `kmet.agent.cli/load-world!`, the
   same list kmet1's entry runs, so the two entries cannot drift.

   **What this row does not do yet.** The mode drives kmet1's engine rather than
   an `:agents` handle: the registry stays empty for a TUI run, and the per-agent
   rows a preset mounts (7b-ii) are not on its path. That is the re-host's job —
   the TUI creates its agent through `create!` and the mode's tree becomes
   `kmet.ui.tui` (new-app.md, landing 5). Mounting it as a row first is what
   makes that re-host a change to one namespace instead of a change to how kmet2
   boots."
  (:require [kmet.agent.cli :as cli]
            [kmet.agent.modes.interactive :as interactive]
            [kmet.srce.core :as srce]))

(defn- run-tui!
  "Load the world, run the TUI on CTX until it quits, and report the exit.

   The entry point waits on `:on-result` (`kmet.app.core/run-mode`), so it fires
   on both paths: the mode owns its output, and a failing TUI reports the
   failure here instead of leaving the entry waiting for a promise that will
   never be delivered."
  [ctx on-result]
  (try
    ;; the composition's ctx, not a process root the world loader would boot: the
    ;; run's tool table, system prompt and session log are the ones it mounted
    (interactive/run (cli/load-world! {:mode :interactive} ctx) {:mode :interactive} ctx)
    (on-result {:exit 0})
    (catch Throwable t
      (binding [*out* *err*]
        (println "Error:" (or (ex-message t) (str (class t))))
        (flush))
      (on-result {:exit 1}))))

(defn- serve
  "The `:mode-tui` row's apply.

   The TUI runs on a thread of its own, as print mode's run does: the mount has
   to finish so the composition can settle before the session starts, and the
   entry point blocks on `:on-result` rather than on the mount. A diagnostic
   mount (`--dump-state`) hands the row no `:on-result` and starts nothing."
  [ctx config]
  (when-let [on-result (:on-result config)]
    ;; the app scope: the row's own ctx is a mount site, and every lookup the
    ;; run makes (:tools, :sessions, :skills) walks up from the scope it was
    ;; given
    (future (run-tui! (:parent ctx) on-result))))

(srce/defplugin plugin
  {:name :mode-tui
   ;; the run reads these from the ctx (`session.driver/bootstrap`'s `:ctx`):
   ;; declare them so a composition that patched one out fails at the mount
   ;; instead of at the first prompt
   :inject [:tools :system-prompt]
   :apply serve})
