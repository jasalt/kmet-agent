(ns kmet.agent.modes.tui
  "The TUI as a mode row: `kmet --app new --profile tui`, the kmet2 entry's
   default profile.

   The row mounts kmet1's interactive mode over the composition the profile
   booted. Two things make that work without moving anything yet: the mode's
   engine takes its ctx as an argument (`kmet.agent.modes.interactive/run`), so
   the run reads the composition's own `:tools`, `:sessions` and
   `:systemPrompt` rows; and the startup a *process* world needs — catalogs,
   config, extension packages, themes — arrives as the config the entry loads
   into this tree before mounting the row (`kmet.app.world`, 5l-ii), the same
   list kmet1's entry runs, so the two entries cannot drift.

   **What this row does not do yet.** The mode drives kmet1's engine rather than
   an `:agents` handle: the registry stays empty for a TUI run, and the per-agent
   rows a preset mounts (7b-ii) are not on its path. That is the re-host's job —
   the TUI creates its agent through `create!` and the mode's tree becomes
   `kmet.ui.tui` (new-app.md, landing 5). Mounting it as a row first is what
   makes that re-host a change to one namespace instead of a change to how kmet2
   boots.

   **The invocation is the run's, and this row only maps it.** `-c`/
   `--continue`, `-r`/`--resume` and `--session <id>` arrive in the row's config
   and become `interactive/run`'s opts: that mode already resolves a session
   from those keys, so the row only has to say that the flags have a home here
   (`:session-lifecycle`, declared where the profile names the row). The run's
   overrides ride the same map — `--model` already resolved to its catalog id,
   `--provider`, `--thinking`, the `--models` scope, and the
   `--system-prompt`/`--append-system-prompt`/`--session-dir` config overrides —
   and `interactive/run` applies them to the config it loads, so a TUI run honors
   them as kmet1's front end always did (`run-opts`). A mode that creates its
   agent through `:agents` has no session option to give its factory yet, and
   `kmet.app.core` refuses the session flags there by name."
  (:require [kmet.agent.modes.interactive :as interactive]
            [kmet.app.invocation :as invocation]
            [kmet.srce.core :as srce]))

(defn- run-opts
  "The invocation's input this row applies, in kmet1's own spellings: the mode
   the world load and the run are told they are, the run's overrides — the model
   pattern `--model` resolved, `--provider`, `--thinking`, the scope
   `--models`, the prompt and session-dir overrides — and the session a run
   opens (`:session`, `:resume`, `:continue`). `interactive/run` applies the
   overrides to the config it loads (`cfg/apply-cli-overrides`) and resolves the
   session from those keys, so this row only maps names, and a key the
   invocation did not give stays absent. A run the command line did not ask to
   continue resolves a new session, as it always did."
  [config]
  (cond-> (merge {:mode :interactive}
                 (select-keys config invocation/run-override-keys))
    (:session config) (assoc :session (:session config))
    (:resume config) (assoc :resume true)
    (:continue config) (assoc :continue true)))

(defn- run-tui!
  "Run the TUI on CTX with the entry's loaded world CONFIG until it quits, and
   report the exit.

   The entry point waits on `:on-result` (`kmet.app.core/run-mode`), so it fires
   on both paths: the mode owns its output, and a failing TUI reports the
   failure here instead of leaving the entry waiting for a promise that will
   never be delivered."
  [ctx config on-result]
  (try
    ;; the composition's ctx, not a process root: the run's tool table, system
    ;; prompt and session log are the ones the profile mounted, and the config
    ;; is the world the entry loaded into that same tree
    (interactive/run (:config config) (run-opts config) ctx)
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
    (when-not (:config config)
      (throw (ex-info (str "the TUI mode needs the entry's loaded world: "
                           "kmet --app new --profile tui")
                      {:type :kmet.app/no-world})))
    ;; the app scope: the row's own ctx is a mount site, and every lookup the
    ;; run makes (:tools, :sessions, :skills) walks up from the scope it was
    ;; given
    (future (run-tui! (:parent ctx) config on-result))))

(srce/defplugin plugin
  {:name :mode-tui
   ;; the run reads these from the ctx (`session.driver/bootstrap`'s `:ctx`):
   ;; declare them so a composition that patched one out fails at the mount
   ;; instead of at the first prompt
   :inject [:tools :system-prompt]
   :apply serve})
