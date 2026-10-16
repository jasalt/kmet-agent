# RPC mode

`--mode rpc` runs kmet headless and long-lived: commands arrive as EDN maps
on stdin, protocol records go out as EDN maps on stdout. It is the transport
for editor plugins, test harnesses, and any program that wants streaming
access to a session without a terminal — and the first front end to answer
extension dialogs asynchronously.

```sh
kmet --mode rpc                          # packaged binary
bb start --mode rpc                      # source checkout (babashka)
jolt start --mode rpc                    # ...or Jolt
```

Session resolution is the same policy print mode uses: `-c/--continue`
resumes the most recent session for the current directory (falling back to a
fresh one when none exists), `--session <id|path>` resumes a specific
session (by id, id prefix, or file path), and with neither flag a fresh
session starts. `--model`, `--provider`, `--session-dir`,
`--system-prompt`, and `--append-system-prompt` all apply. `-r/--resume` is
TUI-only and is not accepted.

## Transport

- One record per line, LF-terminated, in both directions. Every line is one
  EDN map; JSON is not used.
- stdout carries protocol records only. The child's diagnostics (warnings, a
  startup failure) go to stderr.
- A command is any map with a `:type`. An optional string `:id` is echoed by
  the matching response; correlate by `:id`, never by order — an active run
  interleaves events with responses.
- A malformed line answers
  `{:type :response :command :parse :success false :error "..."}` with no
  `:id` (there is nothing to correlate it with) and the loop keeps serving.
- An unknown `:type` and a missing `:type` fail as responses, never as a
  crashed process.

```clojure
;; stdin
{:type :get-state :id "req-1"}

;; stdout — the response
{:type :response :id "req-1" :command :get-state :success true :data {...}}
;; stdout — a run's events arrive in the same stream
{:type :message-update :delta {:type :text :content "hi"}}
{:type :agent-settled :outcome :completed}
```

Events are the print-mode wire projection (`kmet.app.event-bus/wire-event`):
the same `{:type ...}` vocabulary as the session stream, with the cumulative
`:message-update` snapshot dropped and error payloads stringified. Streaming
consumers watch `:message-update` deltas and stop at `:agent-settled`, whose
`:outcome` is `:completed`, `:error`, or `:aborted`.

## Discipline

- **Accepted is not completed.** A `prompt` response reports the disposition
  (`:started`, `:queued`, or `:handled`) as soon as the engine accepts the
  message; the client waits for `:agent-settled` to know how it ended. A
  `:handled` prompt (a slash command such as `/name`) starts no run, so no
  settle follows it.
- **A command failure is a response, a run failure is an event.** A rejected
  command answers `:success false :error "..."`; a crashing run settles with
  `outcome :error` and its error reaches the stream (and the session).
- **Commands are served serially.** A blocking command (`abort`,
  `new-session`) delays the next line; run work streams on its own thread.
- **stdin EOF requests shutdown.** The engine cancels an in-flight run,
  streams its terminal `:agent-settled` (`:aborted`), then disposes and
  exits 0. The session file is appended as entries stream, so an abrupt exit
  loses nothing.
- No SIGTERM/SIGHUP handlers are installed yet; signal an orderly shutdown
  with stdin EOF.

## Commands

### Prompt and queues

| Command | Fields | Response data |
|---|---|---|
| `prompt` | `:message` (required), `:images`, `:streaming-behavior` (`:steer`/`:follow-up`) | `{:disposition :started|:queued|:handled}` |
| `steer` | `:message` | `{:disposition :queued}` |
| `follow-up` | `:message` | `{:disposition :queued}` |
| `abort` | — | `{:idle true|false}` (bounded wait for idle) |
| `clear-queue` | — | `{:steering [...] :follow-up [...]}` (the dropped texts) |

While a run is active a `prompt` must name its `:streaming-behavior` (`:steer`
or `:follow-up`); pi errors without one, so an unqualified second prompt
fails instead of silently queueing. `:images` ride the started path into the
run; the steer/follow-up queues carry no image blocks yet and reject them.

A `prompt` whose text begins with `/` first dispatches through the command
registry (see [Portable slash commands](#portable-slash-commands)); only when
no command matches does the text reach the model.

An `abort` while a run is in flight drops the steering/follow-up queues (kmet
persists the drop as an `:aborted` `:queue-update`). Send `clear-queue` first
if the queued texts matter.

### Session

| Command | Fields | Response data |
|---|---|---|
| `new-session` | — | `{:cancelled bool :session-id ... :session-file ... :was-running bool}` |
| `get-state` | — | model, provider, thinking level, queue modes, `:turn-running?`, `:compacting?`, `:last-outcome`, pending count, session summary |
| `get-messages` | — | `{:messages [...]}` (the live model context) |
| `get-last-assistant-text` | — | `{:text ...}` |
| `get-session-stats` | — | token/tool totals (the `/session` numbers) |
| `export-html` | `:output-path` (optional) | `{:path ...}` |
| `switch-session` | `:session-path` (required) | `{:cancelled bool}` |
| `fork` | `:entry-id` (required) | `{:cancelled bool :text ...}` for the client editor |
| `clone` | — | `{:cancelled bool}` |
| `get-entries` | `:since` (optional entry id) | `{:entries [...] :leaf-id ...}` |
| `get-tree` | — | `{:tree [...] :leaf-id ...}` |
| `set-session-name` | `:name` (required) | — |

Session switches emit their lifecycle events on the same stream, so a
subscribed client sees the swap happen.

### Model, thinking, and queue modes

| Command | Fields | Response data |
|---|---|---|
| `set-model` | `:provider`, `:model-id` (required) | the switched model record |
| `cycle-model` | — | `{:model ... :thinking-level ... :scoped? ...}` or nil |
| `get-available-models` | — | `{:models [...]}` (authenticated models) |
| `set-thinking-level` | `:level` | — |
| `cycle-thinking-level` | — | `{:level ...}` or nil |
| `get-available-thinking-levels` | — | `{:levels [...]}` (the model's set) |
| `set-steering-mode` | `:mode` (`:all`/`:one-at-a-time`) | — |
| `set-follow-up-mode` | `:mode` (`:all`/`:one-at-a-time`) | — |

### Compaction

| Command | Fields | Response data |
|---|---|---|
| `compact` | `:custom-instructions` (optional) | `{:accepted true}` |
| `set-auto-compaction` | `:enabled` (boolean) | — |

`compact` launches on a future and answers `:accepted` immediately — the
outcome arrives as `:compaction-end` / `:session-compact(-failed)` events
(kmet's manual compaction stays escape-cancellable). A run in flight refuses
with a failure response instead of aborting it. `cycle-model` and
`cycle-thinking-level` answer nil data when the model offers fewer than two
choices.

### Commands registry

| Command | Fields | Response data |
|---|---|---|
| `get-commands` | — | `{:commands [{:name :source :description? :argument-hint?}]}` |

`get-commands` lists the registry filtered to this front end — the portable
builtins (`:source :builtin`), extension commands (`:extension`), prompt
templates (`:prompt`), and skills (`:skill`). Interactive-only commands are
filtered out.

## Portable slash commands

A `prompt` whose trimmed text is `/<name> ...` dispatches through the same
registry the TUI uses, with the command context
`{:runtime engine :ui rpc-command-ui :frontend :rpc}`:

- `/continue`, `/name`, `/export`, `/compact` — the engine-portable builtins
- extension commands registered for any front end

The response is `{:disposition :handled}`; nothing reaches the model. Unknown
slash text stays a normal prompt. `get-commands` reports this same filtered
view, so a client can build a command palette without guessing.

## Extension UI

The RPC port binds `{:mode :rpc :has-ui true}`: an extension's `ctx.mode` is
`:rpc` and `ctx.has-ui` is true. Dialogs and output channels cross the wire as
`:ui-request` records carrying their own `:id`.

### Dialogs (request/response)

`select`, `confirm`, `input`, and `editor` forward as requests and wait for
the client's answer:

```clojure
;; stdout
{:type :ui-request :id "uuid" :method :confirm :title "Allow?" :message "Run it?"}

;; stdin — resolve it
{:type :respond :id "uuid" :confirmed true}
;; :value <any> is the general answer; :cancelled true resolves the default
```

A valid answer is silent (pi writes no `extension_ui_response` record); a
`respond` without a string `:id` fails as a response. A request carrying a
`:timeout` auto-resolves with its default; a request never answered resolves
at shutdown. `:custom` has no client surface (pi limitation) and keeps the
headless default.

### Output channels (fire-and-forget)

| `:method` | Fields | Meaning |
|---|---|---|
| `:notify` | `:message`, `:notify-type?` | toast/warning (`ui.notify`) |
| `:set-status` | `:status-key`, `:status-text` | footer status channel |
| `:set-widget` | `:widget-key`, `:widget-lines`, `:widget-placement?` | string lines only; widget hiccup stays TUI-only |
| `:set-title` | `:title` | window/terminal title |
| `:chat-info` | `:label?`, `:content` | informational chat entry |
| `:set-editor-text` | `:text` | put text in the client's editor |
| `:paste-to-editor` | `:text` | as `:set-editor-text` |

TUI-only capabilities without a wire form stay inert (or degrade to the
headless default) and are listed in
[`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md); the client can ignore
fire-and-forget records it does not render.

## Scripting with `kmet.modes.rpc-client`

The repository ships a thin EDN client (`modules/app/src/kmet/modes/rpc_client.clj`) for
Clojure scripts — it spawns the process, correlates responses by `:id`, and
subscribes to the event stream:

```clojure
(require '[kmet.modes.rpc-client :as client])

(def c (client/start {:args ["--provider" "opencode-go"
                             "--model" "deepseek-v4.1-flash"]}))

;; a request/response round trip (throws on timeout or process exit)
(let [state (client/request! c {:type :get-state})]
  (println (:model (:data state))))

;; stream a prompt: arm the settle waiter before sending (see below)
(let [{:keys [settle]} (client/on-settle! c)
      resp (client/request! c {:type :prompt :message "summarize the diff"})]
  (when (= :started (:data resp))
    (println "settled:" (:outcome (deref settle 60000 nil)))))

;; or let wait-idle! probe the state, covering a run that already finished
(println (client/wait-idle! c))

(println "exit" (client/stop! c))   ;; stdin EOF, bounded wait, exit code
```

- `start` options: `:command` (default `["bb"/"jolt" "start"]`; a dist binary
  goes here), `:args`, `:cwd`, `:env`, `:on-event`, `:on-stderr`, plus the
  timeout knobs `:request-timeout-ms` (30 s), `:idle-timeout-ms` (60 s), and
  `:stop-timeout-ms` (5 s).
- `request!` returns the full response map; check `:success` or read `:data`.
  It throws an `ex-info` with `:type :rpc-timeout` or `:rpc-exited`.
- `on-event!` callbacks run on the reader thread — keep them fast.
- `on-settle!` installs its subscription synchronously, so arming it before
  a prompt cannot miss that prompt's settle. `wait-idle!` probes `get-state`
  first, so a run that finished before the call is reported instead of
  waiting for the next one.
- `stderr` and `unparsed-lines` expose the child's diagnostics and any
  stdout line that was not a protocol record (treat a non-empty
  `unparsed-lines` as a bug).

## Compatibility with pi

The command names and record shapes follow pi's RPC mode. Supported:
`prompt`, `steer`, `follow_up`, `abort`, `clear_queue`, `new_session`,
`get_state`, `get_messages`, `get_last_assistant_text`, `get_session_stats`,
`export_html`, `switch_session`, `fork`, `clone`, `get_entries`, `get_tree`,
`set_session_name`, `set_model`, `cycle_model`, `get_available_models`,
`set_thinking_level`, `cycle_thinking_level`,
`get_available_thinking_levels`, `set_steering_mode`, `set_follow_up_mode`,
`compact`, `set_auto_compaction`, `get_commands`, `respond`.

Deferred (no kmet equivalent yet): `bash`, `abort_bash`, `set_auto_retry`,
`abort_retry` — kmet's bash path is the interactive `!` flow, and the retry
knobs have no live setter.

Known differences to account for in a client:

- A running `abort` drops the steering/follow-up queues (kmet's cancel
  semantics); send `clear_queue` first to capture the texts.
- `steer`/`follow_up` do not run extension input hooks (kmet's hooks live in
  the submit path) and reject `:images` until the queues carry image blocks.
- `get_state`'s model is the id plus provider, not a full model object;
  `get_available_models` fills that in.
- Commands are served serially, so a blocking `abort`/`new_session` delays
  the next line.
- `compact` answers `:accepted` and reports the outcome via events rather
  than awaiting a `CompactionResult` in the response.
- No SIGTERM/SIGHUP handlers; end with stdin EOF.
