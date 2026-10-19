# Splitting `kmet.agent.loop` — plan

Temporary note; fold the module map into
[`modules/agent/src/kmet/README.md`](modules/agent/src/kmet/README.md) once the
last landing is in.

Symbols are the locator. Line hints below are only for the first read — they go
stale after the first commit, so move forms by name, never by range.

## Goal

Give each part of the loop one job and a real seam:

- message construction and transcript repair are pure and need no state;
- every mutation of `AgentState` has one owner;
- the provider call and the tool-batch executors are separate units;
- compaction orchestration is separate from the turn driver;
- `kmet.agent.loop` stays the **turn driver** (the prompt/attempt state
  machine) and the entry-point namespace.

This is not a quest for a small `loop.clj` at any cost. There is no delegating
facade, no `def` alias, and no new public surface beyond what another module
actually calls.

Current shape: 3,359 lines, 128 top-level defs (80 private), a 41-field
`AgentState` with 68 in-place mutations across 28 fields, 47 public vars, and a
6,808-line test namespace with 216 tests and 165 `with-redefs`. The split is
worth doing only because it creates seams the tests can use; line count alone
is not the metric.

## Shape

| Module | ~lines | Requires | Contents |
|---|---|---|---|
| `kmet.agent.loop.message` | 175 | — | constructors (`user-message`, `assistant-message`, `tool-result-message`), image blocking, `drop-incomplete-tool-calls`, `normalize-content` |
| `kmet.agent.loop.state` | 950 | message | `AgentState`, `make-agent-state`, `emit`, appends/`*run-token*`, queues and pending messages, bash/custom injection, context/session accessors, settings setters |
| `kmet.agent.loop.completion` | 235 | state, message | tool-call accumulator, `resolve-endpoint`, `resolve-api-key`, `call-llm` |
| `kmet.agent.loop.tool-exec` | 260 | state, message | prepare/finish hooks, parallel + sequential executors |
| `kmet.agent.loop.compact` | 530 | state, completion | summarization, `compact-context!`/`maybe-compact!`/overflow, branch summary |
| `kmet.agent.loop` | 1,200 | all | attempt routes, boundaries, `run-agent-turn`, `cancel-turn`, `make-agent-state` |

Source files: `modules/agent/src/kmet/agent/loop/{message,state,completion,tool_exec,compact}.clj`
(the repo's snake_case filenames). The driver stays `kmet/agent/loop.clj`.

```
message    (no loop.* deps)
state      -> message
completion -> state, message
tool-exec  -> state, message
compact    -> state, completion
loop       -> message, state, completion, tool-exec, compact
```

`state` may require `kmet.agent.runtime` for the `:node` default —
`kmet.agent.runtime` requires only `loop-guard`/`retry`, so there is no cycle.
`session.runtime` requires the submodules it calls (see landing 2); it is not a
front end, so `test_layer_guard` does not constrain it.

## State ownership

Each atom in `AgentState` has one owner after the split. Other modules change a
field only through that owner's function; only the driver may `reset!` its own
control fields. This is a review rule — the DAG test sees requires, not
`swap!`s.

| Field(s) | Owner |
|---|---|
| `:messages`, `:session`, `:run-messages`, `:run-token` | `state` (append/record functions; `*run-token*` binding) |
| `:steering`, `:follow-up`, `:pending-custom`, `:pending-next-turn`, `:pending-bash` | `state` (`steer!`, `follow-up!`, `inject-*`, `flush-*`) |
| `:status` | `state` (`set-agent-status!`) |
| `:model`, `:provider`, `:thinking`, `:scoped-models`, `:cfg`, active tools, system-prompt fields | `state` setters |
| `:compacting?`, `:compaction-signal`, `:compaction-blocked`, `:compaction-error` | `compact` (`compact-context!`, `abort-compaction!`) |
| `:signal` | `state` (`set-run-signal!`; the driver and manual compaction call it) |
| `:last-outcome`, `:retry-count`, `:overflow-recovered`, `:active-call`, `:loop-guard` | driver (`cancel-turn`, `prepare-run!`, the routes) |

One write needs a home when its owner moves: `compact-context!` currently does
`(reset! (:signal agent) false)` on the manual path (a stale Escape must not
abort a manual compaction). Add `state/set-run-signal!` and have both that
path and the driver use it, instead of leaving a compact → driver field write.

## Rules

- **The driver keeps the entry points.** `run-agent-turn` and `cancel-turn`
  stay defined in `kmet.agent.loop`; the driver is not a facade. The record
  lives in `state`, so `kmet.agent.loop/make-agent-state` is the one
  delegated `defn` (a wrapper, never a `def` alias) and carries a pointer
  docstring.
- **Direct requires, no wrapper layer.** `kmet.agent.loop` has exactly two
  source callers: `session/runtime.clj` (36 vars) and `commands/builtins.clj`
  (1). Both require the submodule that owns the var. Precedent: `runtime`
  already requires `kmet.agent.loop-guard` directly.
- **Public only when another module or the entry API needs it.** Everything
  else stays `defn-`. A var that exists only to satisfy a test is public *and*
  carries a docstring saying so (or the test goes through behavior).
- **A moved var moves its interception points.** Production calls the module
  var, so every `with-redefs` and `#'` target moves with it. The audit list is
  in each landing.
- **One `*run-token*`.** Defined in `state`, bound in the driver as
  `state/*run-token*`; never bind a facade copy.
- **No cycles.** The DAG above is enforced by a guard test (landing 2a).
- **Names serve the caller.** Three obvious names are already taken by
  requires in `loop.clj`; do not pick them.
- **Tests move with the code.** A landing moves the direct-call tests for its
  module. Tests that drive a turn stay in `test_loop.clj` (a later, separate
  pass can split those by subject); do not let test movement block a landing.
  New files are `modules/agent/test/kmet/agent/test_loop_<module>.clj`
  (`kmet.agent.test-loop-<module>`); discovery already covers the
  `modules/agent/test` root.
- **Do not sweep ranges.** Each block below is non-contiguous. Move the named
  top-level forms in one pass and prune the now-unused `:require`s (lint
  catches them).

## Move mechanics

Cut forms by name, not by range. A scripted pass keeps a 3,359-line move
reviewable and catches the two failure modes (a form left behind, a form moved
twice):

1. Build the symbol → module map from the landing lists. Assert every
   top-level `def`/`defn`/`defrecord` in `loop.clj` appears exactly once and
   no name is unassigned.
2. Cut and append with a form-aware reader (rewrite-clj/edamame) or
   `clojure_edit` one form at a time; keep the original order inside a module.
3. Qualify the call sites by token (`foo` → `state/foo`), then prune requires.
4. `bb check` before touching tests; the compiler is the completeness check.
5. Run `bb test-changed`; a missing public/private flip fails as a "var is not
   public" compile error, not silently in a test.

### Name collisions to avoid

| Plan name | Use instead | Why |
|---|---|---|
| `loop.llm` | `loop.completion` | `loop.clj` already requires `kmet.ai.llm` as `llm` |
| `loop.tools` | `loop.tool-exec` | `kmet.agent.tools.core` is already `tools` |
| `loop.compaction` | `loop.compact` | `kmet.agent.compaction` stays the pure prompt/serialize layer and is already `compaction` |

`kmet.agent.loop.state` and `kmet.agent.loop.message` do not collide. The
existing satellites (`kmet.agent.loop-guard`, `kmet.agent.retry`) keep their
hyphen names; do not rename them in this change.

## Landings

Landings 1–2 are one series; 3–4 are a second. Each landing is a revertible
unit — do not mix two landings in one commit, and do not start a landing with
a half-updated call-site table.

### 1. `loop.message`

Move, by name:

```
user-message  assistant-message  tool-result-message  normalize-content
blocked-image-placeholder  append-blocked-placeholder
block-images-content  block-message-images
drop-incomplete-tool-calls
```

Make public: all but `blocked-image-placeholder`,
`append-blocked-placeholder`, and `block-images-content`. The public ones are
used by `state` (`user-message`, `assistant-message`, `normalize-content`,
`drop-incomplete-tool-calls`), `completion`
(`block-message-images`), `tool-exec` and the driver
(`tool-result-message`).

In `loop.clj`: add `[kmet.agent.loop.message :as message]`, qualify the call
sites, delete the moved forms. `loop.clj` still holds the compaction and
provider code at this point, so it calls `message/block-message-images` from
`call-llm` and `message/tool-result-message` from the executors.

Tests:

- New `test_loop_message.clj` (`kmet.agent.test-loop-message`): move
  `test-loop-drop-incomplete-tool-calls` and
  `test-loop-block-images-noncanonical-tool-message` — the only tests that
  call the functions directly.
- `test-loop-block-images-*` drive a turn and stay until the later test pass.
- No `with-redefs` audit needed: nothing in this module is stubbed.

Gate: `bb check`, `bb test-changed`, `bb lint-changed`,
`bb format-check-changed`.

### 2. `loop.state` (the big one)

Move, by name, grouped by concern — not by range:

```
AgentState  make-agent-state
active-tools-for  active-tools  set-active-tools!  apply-default-tools!
emit  set-agent-status!  append-persisted-message!
queue-batch  drain-queue!
*run-token*  record-run-message!  append-context-message!  append-message!
add-user-message!  add-assistant-message!  record-abandoned-attempt!
add-custom-message!  append-custom-message!
enqueue!  steer!  follow-up!  clear-queues!  queued-messages
has-queued-messages?  peek-queued-messages
add-bash-result!  flush-pending-bash-messages!  add-context-message!
defer-custom-message!  queue-next-turn-message!  flush-pending-custom-messages!
inject-next-turn-messages!  inject-queued-messages!  drain-next-queue!
refresh-context-from-session!  replace-context!
get-context  restore-session-context!  append-context-edit!  apply-session-settings!
set-model!  set-scoped-models!  init-scoped-models!  set-block-images!
set-auto-compact!  queue-batch-modes  set-queue-batch-mode!
set-steering-mode!  set-follow-up-mode!  set-http-idle-timeout-ms!
set-http-total-timeout-ms!  scoped-model-entries  cycle-model!
set-thinking-level!  cycle-thinking-level!  switch-thinking-level
```

Keep private: `queue-batch`, `record-run-message!`, `append-message!`,
`enqueue!`, `queue-batch-modes`, `set-queue-batch-mode!`, `scoped-model-entries`.

Public because the driver or `completion`/`compact` calls them: `emit`,
`set-agent-status!`, `append-persisted-message!`, `*run-token*`,
`add-user-message!`, `add-assistant-message!`, `record-abandoned-attempt!`,
`append-context-message!`, `refresh-context-from-session!`,
`replace-context!`, `inject-next-turn-messages!`, `inject-queued-messages!`,
`drain-next-queue!`, `flush-pending-custom-messages!`,
`flush-pending-bash-messages!`, `drain-queue!` (also the test seam).

In `kmet.agent.loop`: add `[kmet.agent.loop.state :as state]` and keep the one
wrapper:

```clojure
(defn make-agent-state
  "Create a new agent state; see kmet.agent.loop.state/make-agent-state for
   the options."
  [& opts]
  (apply state/make-agent-state opts))
```

Land this as two commits in one series, both green:

- **2a — record, messages, and context:** `AgentState`, `make-agent-state`,
  `emit`, status/persist, `*run-token*` and the append/record machinery
  (`record-run-message!`, `append-context-message!`, `append-message!`),
  `add-user-message!`, `add-assistant-message!`, `record-abandoned-attempt!`,
  `add-custom-message!`, `append-custom-message!`, `add-bash-result!`,
  `flush-pending-bash-messages!`, `add-context-message!`,
  `flush-pending-custom-messages!`, `queue-batch`, `drain-queue!`,
  `inject-next-turn-messages!`, `inject-queued-messages!`, `drain-next-queue!`,
  the context/session accessors (`get-context`, `restore-session-context!`,
  `append-context-edit!`, `refresh-context-from-session!`, `replace-context!`),
  active tools, and the settings setters.
- **2b — queue atoms:** `enqueue!`, `steer!`, `follow-up!`, `clear-queues!`,
  `queued-messages`, `has-queued-messages?`, `peek-queued-messages`,
  `defer-custom-message!`, `queue-next-turn-message!`, `queue-batch-modes`,
  `set-queue-batch-mode!`, `set-steering-mode!`, `set-follow-up-mode!`.

Keep the split along that line: 2a is everything that **writes the message
stream** (and therefore owns the private append helpers), 2b is everything
that only **enqueues or inspects a queue atom**. Do not move `drain-queue!`
into 2b — the 2a injectors call it. Each commit retargets the call sites for
the vars it moves; the table below is the union.

If the state module is still over ~1,000 lines after 2b, stop and carve
`loop.queue` out: queues, pending-message injection/flushes, and their modes
move to `loop.queue` (which requires `state`); the record, appends, context,
and settings stay in `state`. Trigger: `state.clj` > 1,000 lines or
`test_loop_state.clj` > 1,500. Do not do it pre-emptively.

Add the DAG guard test in 2a with the allowed-edge map for the modules this
plan ends with (extend it if `loop.queue` is carved): it is a subset check, so
it passes while the remaining modules still live in `loop.clj` and guards the
later landings. Put it in `test_layer_guard.clj`, reusing that file's
`ns-form`/`required-kmet` helpers (make them public).

While `make-agent-state` is delegated, the record and the real constructor are
in `state`; `test_loop` and the other mode tests keep calling
`loop/make-agent-state` unchanged.

Source call sites:

| File | Change |
|---|---|
| `session/runtime.clj` | add `[kmet.agent.loop.state :as agent-state]`; retarget the 31 state vars listed in the landing audit below; keep `agent/run-agent-turn` (1) and `agent/cancel-turn` (2) |
| `commands/builtins.clj` | replace the loop require with `[kmet.agent.loop.state :as agent-state]`; `agent/get-context` → `agent-state/get-context` |
| `session/runtime.clj` docstrings | `kmet.agent.loop/switch-thinking-level` → `kmet.agent.loop.state/switch-thinking-level` |

Make `loop-guard-give-up!` private in `kmet.agent.loop` — the driver is its
only caller.

The 31 state vars in `session/runtime.clj`:
`active-tools`, `active-tools-for`, `add-bash-result!`, `add-context-message!`,
`append-custom-message!`, `apply-default-tools!`, `apply-session-settings!`,
`clear-queues!`, `cycle-model!`, `cycle-thinking-level!`,
`defer-custom-message!`, `follow-up!`, `get-context`, `has-queued-messages?`,
`init-scoped-models!`, `make-agent-state`, `queue-next-turn-message!`,
`queued-messages`, `restore-session-context!`, `set-active-tools!`,
`set-auto-compact!`, `set-block-images!`, `set-follow-up-mode!`,
`set-http-idle-timeout-ms!`, `set-http-total-timeout-ms!`, `set-model!`,
`set-scoped-models!`, `set-steering-mode!`, `set-thinking-level!`, `steer!`,
`switch-thinking-level`.

Test call sites (all mechanical alias retargets; the constructors and
`run-agent-turn` stay on `agent`):

| File | Change |
|---|---|
| `modes/test_interactive.clj` | `agent/set-block-images!` → `agent-state/set-block-images!` |
| `modes/test_rpc.clj` | `agent/restore-session-context!` → `agent-state/restore-session-context!` |
| `test_interactive_ui.clj` | `agent/active-tools`, `queued-messages`, `set-active-tools!`, `set-scoped-models!`, `steer!` → `agent-state/` |
| `test_loop.clj` | add `[kmet.agent.loop.state :as state]`; retarget the 29 `loop/...` state tokens in its audit list; `loop/make-agent-state`, `loop/run-agent-turn`, `loop/cancel-turn` unchanged |

`test_loop.clj` state tokens to retarget:
`active-tools`, `add-bash-result!`, `add-context-message!`,
`append-context-edit!`, `apply-default-tools!`, `apply-session-settings!`,
`clear-queues!`, `cycle-model!`, `defer-custom-message!`, `drain-queue!`,
`flush-pending-bash-messages!`, `follow-up!`, `get-context`,
`has-queued-messages?`, `init-scoped-models!`, `peek-queued-messages`,
`queue-next-turn-message!`, `queued-messages`, `restore-session-context!`,
`set-active-tools!`, `set-auto-compact!`, `set-block-images!`,
`set-http-idle-timeout-ms!`, `set-http-total-timeout-ms!`, `set-model!`,
`set-scoped-models!`, `set-thinking-level!`, `steer!`, `switch-thinking-level`.

Also `#'loop/drain-queue!` (two refs) → `state/drain-queue!`, now public.

Move to `test_loop_state.clj` (direct-call tests only): the
`make-agent-state` / `get-context` / `set-active-tools` / `restore-session-context`
/ `apply-session-settings` / model / thinking / scoped-model / queue-mode /
`append-context-edit` / `add-bash-result` / http-timeout setter /
`active-tools` / `apply-default-tools` tests. The cancel, status-transition,
run, and hook tests stay in `test_loop.clj`.

Gate: **full** `bb test` / `bb test-ext` / `bb lint` / `bb format-check` —
this touches every path. This is the reassessment point: if `state` is still
over ~1,000 lines after the queue block lands, take the `loop.queue` split
before landing 3.

### 3. `loop.completion` + `loop.tool-exec`

Both depend only on `state` (and `message`), so land them together.

`completion`:

```
make-tc-accumulator  resolve-endpoint  resolve-api-key  call-llm
```

Public: `resolve-endpoint`, `resolve-api-key`, `call-llm` (called by
`compact` and the driver). `make-tc-accumulator` stays private.

`tool-exec`:

```
has-sequential-tool-call?  await-all-tool-results!  tool-on-update
prepare-tool-call!  finish-tool-call!
execute-tool-calls-parallel!  execute-tool-calls-sequential!  execute-tool-calls!
```

Public: `has-sequential-tool-call?` (the driver's batch decision, and the test
seam) and `execute-tool-calls!` (the driver). The rest stay private.

In `kmet.agent.loop`: add `[kmet.agent.loop.completion :as completion]` and
`[kmet.agent.loop.tool-exec :as tool-exec]`; qualify `call-llm`,
`resolve-endpoint`, `resolve-api-key`, and `execute-tool-calls!`; delete the
moved forms.

Tests:

- `loop/has-sequential-tool-call?` → `tool-exec/has-sequential-tool-call?`.
- Nothing else in `test_loop.clj` calls these vars directly: the provider
  tests stub `llm/send-message` and label their turns through
  `run-agent-turn`, so they stay.
- The direct `call-llm` timeout tests (if any call the var rather than a turn)
  move to `test_loop_completion.clj`.

Gate: `bb check`, `bb test-changed`, `bb lint-changed`,
`bb format-check-changed`.

### 4. `loop.compact`

Move, by name:

```
abort-compaction!  compaction-aborted?  summarization-retry-callbacks
summarization-call  compaction-settings  summarization-max-tokens
stage-retry-policy  summarize!  generate-branch-summary
omit-recovery-attempt!  record-compaction!  finish-compaction!
compact-context!  maybe-compact!  compact-for-overflow!
```

Public because the driver calls them: `abort-compaction!`,
`generate-branch-summary`, `compact-context!`, `maybe-compact!`,
`compact-for-overflow!`, `omit-recovery-attempt!`, `stage-retry-policy`.
Keep private: the summarization helpers, `record-compaction!`,
`finish-compaction!`.

In `kmet.agent.loop`: add `[kmet.agent.loop.compact :as compact]`; qualify the
calls in `run-attempt!`, `attempt-routes`, and `prepare-run!`; delete the moved
forms. `refresh-context-from-session!` and `replace-context!` are already in
`state`; `compact` calls `state/...`.

Source call sites:

| File | Change |
|---|---|
| `session/runtime.clj` | add `[kmet.agent.loop.compact :as agent-compact]`; `agent/compact-context!` (2), `agent/abort-compaction!` (1), `agent/generate-branch-summary` (1) → `agent-compact/`; update the `kmet.agent.loop/compact-context!` docstring pointer |
| `test_session_runtime.clj` | `with-redefs` targets `agent/compact-context!` and `agent/generate-branch-summary` → `agent-compact/` |
| `test_interactive_ui.clj` | `with-redefs` target and call `agent/compact-context!` → `agent-compact/compact-context!` |

`test_loop.clj` compact tokens to retarget, plus the `with-redefs` audit:

| Token | Target |
|---|---|
| `abort-compaction!`, `compact-context!`, `generate-branch-summary` | `compact/` |
| `maybe-compact!` (direct calls **and** `with-redefs` at the mid-run compaction test) | `compact/` |

The `with-redefs` on `loop/maybe-compact!` is the one that silently stops
intercepting if it is missed: the driver calls `compact/maybe-compact!`, so a
stub on `loop/` would never run.

Move to `test_loop_compact.clj` (direct-call tests only): `compact-no-op`,
`compaction-refuses-when-active`, `compact-extension-cancel`,
`manual-compaction-already-compacted`, `empty-summary-is-appended`, and the
`branch-summary` tests. Run-driven overflow and threshold tests stay in
`test_loop.clj`.

Add the entry-point pin now (the DAG test landed in 2a):

- `the-loop-entry-point-is-pinned`: assert
  `(= '#{make-agent-state run-agent-turn cancel-turn}
      (set (keys (ns-publics 'kmet.agent.loop))))`.

Gate: **full** suite again.

## Verify

```sh
bb check
bb test-changed
bb lint-changed
bb format-check-changed
bb start -p "say hi"          # if a provider is configured
```

Full gates after landings 2 and 4. Between landings, `bb changed` is the map;
do not run the full suite during iteration.

`bb check` is the compile check for a structural move; do not use a hot REPL
reload as the only verification (a stale var can survive in the image).

When a moving test needs a shared helper (`run-turn-capturing-llm`,
`with-summarization-stub`, `make-test-provider`, the `stub-llm-*` family), move
the helper to `kmet.test-utils` (root `test/`) instead of copying it into the
new namespace.

## Docs

Per landing, move the matching paragraph of the `kmet.agent.loop` docstring to
the new namespace's docstring, leaving a one-line pointer:

| Landing | Paragraph |
|---|---|
| 1 | "Image attachments" (and the `:block-images` note) |
| 2 | "Queues", "Model management"; the state/mutation overview |
| 3 | "Tool hooks", "Tool execution mode", the provider-call notes |
| 4 | "Compaction", "Auto-retry" |

After landing 4:

- add the `loop/` subtree to the project-structure block in
  `modules/agent/src/kmet/README.md` (the `loop.clj` line at ~93);
- write the turn lifecycle into the `kmet.agent.loop` ns docstring: the
  prompt loop (`run-agent-turn` → `prepare-run!` → attempt →
  `attempt-routes` → settle), the attempt loop (`run-attempt!`:
  LLM → tools → steer → follow-up), the boundaries (`:turn-end`,
  `:agent-before-settle`), and the state fields each step may mutate. This is
  the map the module split follows, and the place to record the route names.
- update the comment pointers: `config.clj:70` (`block-message-images`),
  `event_bus.clj:5,359` (`emit`, `run-agent-turn`), `compaction.clj:7`,
  `loop_guard.clj:5`, `retry.clj:6`, `session/runtime.clj:1023,1055,1257`,
  `skills.clj:461` (`execute-tool-calls-parallel!`), `tools/bash.clj`,
  `tools/util.clj`, and the matching entries in `pi-alignment.md`;
- update function docstrings that name a moved var: `run-attempt!`'s outcome
  table (`record-abandoned-attempt!`, `omit-recovery-attempt!`),
  `attempt-routes`' comments (`maybe-compact!`), `emit`'s two-sink note, and
  `cancel-turn`'s `:active-call` note.

## Not doing

- A facade of delegating `defn`s for the state vars. Direct requires cost
  two `ns` edits; a facade costs a permanent mirror, duplicated docstrings,
  and a "derive the list with grep" step that no test enforces.
- `AgentLoopConfig` — `make-agent-state` already injects every hook/knob, and
  `test_loop` builds a state without a session.
- A blind re-export mirror or `def` aliases.
- Moving compaction execution into `session.runtime` — the runtime owns the
  queue; the loop owns execution and overflow recovery.
- Splitting `state` further pre-emptively (the pure message layer is the
  split); the conditional `loop.queue` carve above is the only sanctioned next
  step, and only when its trigger fires.
- Renaming `kmet.agent.loop-guard`/`kmet.agent.retry` to fit the new family.

## Follow-up (not this plan)

The split does not make the driver easier to reason about: `run-attempt!` is
still a nested `loop` with an inner `case` and closure-valued routes, and
`attempt-routes` is a table of effects. The next win is to make the
**decision** side pure — `(next-action state outcome)` for the
overflow/backoff/terminal dispatch and the phase transitions — so those paths
can be tested without `with-redefs` on provider calls. `retry/retry-decision`
already has the error classification; this is mostly wiring. Do it after the
module split, in its own change.

A smaller follow-up: the state surface is still ~31 operations, most of them
one-line config setters (`set-block-images!`, `set-auto-compact!`,
`set-http-*-timeout-ms!`, the queue-mode setters). Once the module boundary is
stable, collapse those into one `update-run-config!` and keep the operations
with real behavior (model cycling, thinking-level policy, session restore).
That shrinks the runtime-facing surface without touching the driver.
