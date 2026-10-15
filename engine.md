# Engine/UI split — working plan

Status: working design note at the repository root (like `run_code.md`,
`perf.md`, `windows.md`). It records decisions, a staged plan, and open
questions. It is not user documentation and it is not authoritative for
behavior — the topic map in `AGENTS.md` still points at the behavior docs.
Fold this into `src/kmet/README.md` (or delete it) when the work lands.

Scope: one session runtime usable unchanged by the interactive TUI, print
mode (text and event output), and the GUI front end. The GUI is postponed
until the Tk backend stabilizes; RPC mode (step 6) goes next. Namespaces
are not renamed.

Decisions already taken in discussion:

- **EDN is the event format** — in-process and on the wire. No JSON
  encoding; the internal shape and the wire shape are one thing. A thin
  wire *projection* (shape, not encoding — pi strips cumulative streaming
  snapshots from `message_update`) may be added for event-output modes if
  a measurement justifies it.
- **The engine is the session runtime, not a new layer.** The work moves
  ownership of session, turn, and bootstrap behavior from
  `modes/interactive` into `kmet.app.*`; it does not add an abstract facade
  over the existing code. If the runtime does not delete code from
  `modes/interactive`, the step failed.
- **No namespace renames.** `kmet.app.*` keeps its names; the split is
  ownership, not naming. (The former step R is dropped.)
- **The GUI is the next visual front end, and RPC the next headless one.**
  The GUI already exists as a display-only viewer
  (`scripts/gui_tk_session.clj`, `app/ui/gui/transcript.clj`); its live-run
  milestone is what exercises the render seam, but it is postponed until
  the Tk backend stabilizes. RPC (step 6) is scheduled instead: the same
  seam from the async-answer side, with no terminal.
- **malli is a boundary tool.** It describes file formats (session
  entries, config, auth) and, later, a wire protocol. In-process events and
  the bootstrap map are shaped by code and tests — pi types its event union
  and schemas only the wire.

---

## 1. Goal and non-goals

Goal: any front end can drive a session through one session runtime and
one event stream without touching loop, session, tools, provider, or
extension internals.

Non-goals (v1):

- **Multi-engine processes.** One runtime per process for now, but
  multi-engine is a confirmed future direction (a headless server with
  parallel sessions). The extension runtime (`kmet.app.extensions` globals)
  stays process-global in v1; the runtime holds its handle and installs it
  at `bootstrap`, so per-engine scoping later is a re-plumbing, not a
  redesign — and no new process-global assumptions are added meanwhile.
- **Sharing view code between front ends.** TUI components, `app/ui/*`, and
  GUI widgets stay per-front-end. What is shared is the session runtime,
  the event stream, and the data providers.
- **A schema program.** malli lands at file boundaries and a future wire
  protocol, not on every event, config section, catalog, or in-memory
  value.
- **A distributed/network protocol.** RPC (step 6) starts at stdin/stdout
  EDN lines.

## 2. Baseline: what already separates

The seam mostly exists today; the plan sharpens it rather than inventing it.

- `kmet.app.loop` (3.2k lines) is UI-free and emits a documented event
  vocabulary (`kmet.app.event-bus`): `event-types`, plus the derived
  `app-event-types` / `loop-event-types` sets. `emit` routes each event to
  the single `:on-event` callback and to `event-bus/emit-event!`
  (extensions).
- `kmet.modes.print` drives the loop headlessly with no TUI — the proof that
  a second front end is viable.
- The GUI front end is already started: `scripts/gui_tk_session.clj` renders
  a saved session through `app/ui/gui/transcript.clj` and `kmet.tk.*`
  (display-only, no loop). The live-run milestone is the plan's real proof.
  The transcript reuses the TUI renderers (markdown, theme, ANSI) on
  purpose — the terminal renderers stay the single source of styling.
- `kmet.ai.*` is a standalone provider/auth library; `kmet.app.tools/*`
  contain no TUI requires.
- `app/ui/*` (~12.1k lines, 36 files) is already fenced as app-specific
  presentation, and the layer rules in `src/kmet/README.md` state the
  direction (`libs` → `tui`/`gui` → `app` → `modes`).

## 3. Findings: what actually blocks the split

1. **No engine bootstrap.** `make-agent-state` wiring is duplicated in
   `modes/print.clj` and `modes/interactive/layout.clj`: system prompt,
   default tools, retry/loop-guard settings, HTTP timeouts, scoped models,
   extension session/context wiring. A GUI mode would be the next copy:
   50+ lines and a key silently missed (the print-mode comments about
   `get-with-default` vs `get` document exactly such a near-miss).
2. **No session owner.** Resume/fork/tree/import live in
   `modes/interactive/session_admin.clj`; submit/steer/cancel/queue lives in
   `modes/interactive/turn.clj`; session-dir helpers plus a global config
   atom live in `modes/interactive/state.clj`. These are runtime behavior.
3. **One `:on-event` callback, no subscription.** `AgentState` holds a
   single callback; a GUI, a future wire client, and an automation listener
   cannot coexist, and there is no unsubscribe.
4. **Engine state and view state share one record.** `CoreState` holds
   `agent-state`, `session-atom`, `running-turn?` next to editors, docks,
   footers, and selectors.
5. **Extension runtime is process-global atoms** (`app/extensions.cljc`:
   `ui-registry`, `session-atom`, `context-sink-atom`). Print mode stubs a
   UI registry to satisfy the same globals.
6. **Six `app/**` namespaces require TUI** (excluding `app/ui`):
   `app/bash_executor.clj` (`tui.utils`), `app/theme_controller.clj`
   (`tui.core`, `tui.theme`), `app/packages.clj` (`tui.theme`),
   `app/keybindings.clj` (`tui.keybindings`), `app/extensions.cljc`
   (`tui.theme`, used by applied resource paths at
   `theme/load-themes-from-dir`, line 1180), and
   `app/extensions/context.cljc` (`tui-library-namespaces` injection —
   §3.8). Small, but they are the ones that would drag the terminal into a
   GUI process. The leak is already live headless: `modes/print.clj`
   requires `app.packages`, and a cold `(require 'kmet.modes.print)` loads
   `kmet.tui.theme` and `kmet.tui.utils`.
7. **No serialization contract.** Events carry partial message snapshots;
   session files are read leniently (malformed line → skip with a warning);
   nothing asserts that an emitted event is plain EDN data.
8. **The extension ABI hard-requires the TUI layer.** `src/kmet/extension.md`
   tells extensions they may depend on `kmet.extension`, `kmet.tui.*`, and
   `kmet.libs.*`; `app/extensions/context.cljc` enforces that allowlist
   (`validate-entry-requires!`) and injects the whole
   `tui-library-namespaces` list into every extension context.
   `host-requires!` (line 1169) `require`s all of them before the first
   per-extension context is built, so any process that loads an extension
   loads the TUI component library — print mode already does (see §3.6).

## 4. Target architecture

```
                    kmet.modes.interactive ─┐
                    kmet.modes.print       ─┤   front ends: input, rendering,
                    kmet.modes.gui (next)  ─┼─  session UX, extension UI port
                    kmet.modes.rpc (later) ─┘
                                │ subscribe / prompt! / respond!
                   ┌────────────┴───────────────┐
                   │  kmet.app.session-runtime  │  owns: session lifecycle,
                   │   (moved from modes +      │  prompts, queues, commands,
                   │    make-agent-state)       │  extension runtime handle
                   └────────────┬───────────────┘
             kmet.ai (provider lib) · kmet.app.tools · kmet.libs.*
                                │
                    kmet.tui.* / kmet.tk.* (per-front-end view backends)
```

Dependency rule (enforced by a test, §8): the runtime may require
`kmet.libs.*` and `kmet.ai.*`, and **never** `kmet.tui.*`, `kmet.tk.*`,
`kmet.modes.*`, or `kmet.app.ui.*`. While `kmet.app.ui` still lives inside
`kmet.app`, the guard excludes that subtree explicitly.

### 4.1 The session runtime

The runtime owns `AgentState`, the `Session` record, the config, the
extension runtime handle, and subscribers. One constructor, one lifecycle.
It is the existing mode code **moved**, not a parallel implementation:

- `modes/interactive/session_admin.clj` + `turn.clj` + `state.clj` and the
  `make-agent-state` wiring become one `kmet.app.*` namespace (provisional
  name: `kmet.app.session-runtime`).
- The operations below are the contract; the namespace name is not.

```clojure
;; kmet.app.session-runtime (provisional name; operations are the contract)
(bootstrap config opts)     ; {:session Session|nil :resume? bool ...}
                            ; -> engine
(dispose! engine)           ; signals runs, clears subscribers + runtime refs

;; prompts and queues
(prompt! engine {:text s :images [...]})   ; non-blocking; events drive
(steer! engine text) (follow-up! engine text) (cancel! engine)
(compact! engine) (queues engine)            ; {:steering [...] :follow-up [...]}

;; session
(new-session! engine) (resume! engine path) (fork! engine entry-id)
(switch-session! engine session)           ; emits the *before* events first
(entries engine) (snapshot engine)

;; model / thinking
(set-model! engine ref) (cycle-model! engine) (set-thinking-level! engine lvl)
(scoped-models engine) (set-scoped-models! engine models)

;; streams
(subscribe! engine f)       ; -> unsubscribe fn
(last-outcome engine)       ; {:outcome :completed|:error|:aborted}
(respond! engine id value)  ; answers a :ui-request (4.5)
```

Rules:

- `prompt!` returns immediately. Completion is observed through
  `:agent-settled` (§4.2), not through a return value; a mode that needs
  blocking (`print`, and a future RPC one-shot) uses its own promise.
- `bootstrap` takes the **resolved** session or a resolution request. Session
  *policy* stays per mode: `--resume` opens the TUI selector, print mode
  errors on no match, a wire mode would take an explicit path.
  `app.session` already provides the mechanics (`resolve-session-path`,
  `find-most-recent-session`, `session-dir-for-cwd`); the runtime adds the
  helpers that today live in `modes/interactive/state.clj`
  (`ensure-cwd-session-dir`, `runtime-cwd`, `find-session`).
- The runtime wraps `run-agent-turn` unchanged in step 1. `:on-text`,
  `:on-thinking`, `:on-done`, `:on-error` stay as sugar **derived from the
  same emission path**, so they cannot drift from the event stream (print
  mode keeps working while it migrates).
- `snapshot` returns plain data; it may mix per-field atom values (the agent
  uses independent atoms). Display-only consumers are fine with that;
  anything needing a consistent cut must document it.
- `dispose!` cancels any in-flight run, then clears subscribers and runtime
  refs. Write the terminal-event contract down: the settle path
  runs in a `finally`, so `:agent-settled` with finding A's `:outcome`
  should reach subscribers before they are cleared.

### 4.2 The event stream (EDN)

The existing vocabulary is the API. No renaming of event types in this work.

Contract rules:

1. **Plain EDN data only**: keywords, strings, numbers, maps, vectors, sets,
   booleans, nil. No records, functions, atoms, `Instant`, or Java objects.
   The test suite enforces `(= ev (clojure.edn/read-string (pr-str ev)))` for
   recorded scenarios.
2. **Subscriber callbacks run on the emitting thread** (today: the loop
   thread; `emit` calls the callback directly). They must be fast and must
   not block. TUI already marshals via `tui-request-render`; a GUI marshals
   to its toolkit thread; a socket-backed subscriber queues.
3. **Subscribers never block the runtime.** If async buffering or coalescing
   is ever needed (high-rate `:message-update`), it is per-subscriber and
   opt-in, not a global queue in the loop.
4. **Extension handlers are different.** They may block (a dialog waits for
   a user, an RPC client answer waits on a promise) — that matches TUI
   semantics today. Only *subscribers* have the non-blocking rule.
5. **The stream is the whole truth for a front end.** If a view needs data
   that is not in the stream, the event gains a field rather than the view
   reaching into `AgentState`. A headless renderer test (§8) enforces this.
6. **Keyword discipline.** Event `:type` and value keywords stay
   unqualified (`:assistant`, `:completed`) and payload keys are plain
   keys; namespaced keys are reserved for stable extension payloads.

### 4.3 Data schemas (malli) — boundaries only

malli schemas describe values that **cross a boundary or outlive the
process**. That means the session file, config, auth, and (only if a wire
mode is ever built) protocol records. In-process events, view models, and
the bootstrap map are shaped by code and tests — pi types its event union
and schemas only its wire.

```clojure
;; kmet.app.session-schema (provisional)
(def entry-schemas
  {:user      [:map {:description "..."} [:type [:= :user]] [:role [:= :user]] ...]
   :assistant [:map {:description "..."} [:type [:= :assistant]] ...]
   ;; one entry per :role, ~15 today
   })

(def Entry (into [:multi {:dispatch :role}] entry-schemas))
```

- Schemas are **open** where the format is forward-compatible (unknown
  keys survive); `:closed true` only where an extra key is a bug.
- The session load path distinguishes "unparseable" (skip, as today) from
  "wrong shape" (report line + `malli.error/humanize`).
- The vocabulary test runs on the keyword sets (`event-types` /
  `app-event-types` / `loop-event-types`), not on a schema map. Event
  schemas exist only if an event-output or wire mode needs them.
- Prose stays in the docs; schema maps carry `:description` and a coverage
  test asserts every schema has one.

The `:kmet.ai.message/*` schemas need a new `kmet.ai.message` namespace.
Messages are plain maps built across the loop and the provider builders
today, so this is a new artifact to schedule, not an existing home to
reference. Its first consumer is the session-entry schema (entries store
messages); provider builders and a future wire reuse it.

### 4.4 Session and state ownership

- `Session` (record with `:header`, `:entries` atom, `:leaf-id` atom, lock)
  stays in `kmet.app.session`; the runtime owns its lifecycle. The mode gets
  it via `(snapshot engine)` and listens for session events.
- Session-dir helpers move out of `modes/interactive/state.clj` into the
  runtime (or into `kmet.app.session`), with config injected rather than read
  from a mode-global atom.
- `AgentState`, `CoreState`, and TUI component records are **not** schema
  targets. Their contract is the runtime API and tests.
- Session switching keeps its two-phase contract: before-events
  (`:session-before-switch`, `:session-before-fork`, `:session-before-tree`)
  are emitted by the runtime and may be cancelled by extensions; the swap
  happens only when no handler cancels. The mode owns the UX around it
  (selectors), not the swap. Today they go straight to
  `event-bus/emit-event!` from `modes/interactive/session_admin.clj`
  (lines 427, 765, 881, 938) — extension-only, not the UI stream; moving
  them under the runtime should decide whether the UI stream sees them too.

### 4.5 Extension runtime and the UI port

Extensions are loaded by the process, but their UI surfaces belong to the
active front end. Split the capability map:

- **Runtime capabilities** (session handle, context sink, tool hooks, event
  bus, command/tool/renderer registries): held on the runtime, installed by
  `bootstrap`, cleared by `dispose!`. The `kmet.app.extensions` globals stay
  process-global in v1; passing the runtime's handle explicitly (no new
  globals) is what keeps a future per-engine scope a re-plumbing (§1).
- **UI port** (`ExtensionUIContext`: widgets, dialogs, editor, statuses):
  installed by the front end. `modes/interactive/ui-registry.clj` is the TUI
  implementation; print mode installs auto-answer defaults; RPC forwards
  requests to the client; GUI builds its own.
- **Requests go to the port, not to subscribers.** §4.2's non-blocking rule
  applies to `subscribe!` callbacks; a dialog waits on the loop thread
  inside the extension handler (today's semantics, §4.2 rule 4). A
  `:ui-request` must reach the front end's UI port directly (a synchronous
  in-process answer for TUI/print), while an async front end (RPC) queues
  the request to its client and answers later with `respond!`. Do not route
  `:ui-request` through `subscribe!`, or the TUI would have to block inside
  a subscriber.
- **Async UI requests.** Synchronous dialogs cannot cross a socket. The
  runtime-level contract is a request/response pair of events:
  `{:type :ui-request :id ... :kind :select|:confirm|:input|:editor ...}`
  answered by `(respond! engine id value)`. TUI implementations may still
  answer synchronously (the request emission can resolve an in-process
  promise); RPC answers when the client responds.
- **The component surface stays `kmet.tui.*`.** The UI port covers the
  widgets/dialogs the host renders, not the components an extension builds
  itself (§3.8). A headless front end therefore loads the TUI component
  library whenever an extension loads; a front end that must avoid that
  needs the shared-component-library split (an unscheduled extraction; the
  GUI does not need it — its transcript reuses the TUI renderers on
  purpose).
- Extension reload semantics (`ui-reset!`, `clear-runtime!`,
  `session-shutdown` before unload) stay as they are; the runtime exposes
  the lifecycle instead of the mode calling the globals directly.

### 4.6 Modes

- `interactive`: subscribes for rendering; `CoreState` shrinks to view state
  plus the runtime reference; `turn.clj`/`session_admin.clj` logic moves
  under the runtime, leaving the handlers and widgets.
- `print`: migrates in step 2 to prove the seam (smallest consumer). Keeps
  its `:on-done` semantics via the settled outcome (§5, finding A), and
  grows an event-output variant (`--mode text|json`, pi's one-consumer
  trick) — the cheapest headless conformance test for the stream.
- `gui` (next): the display-only viewer exists
  (`scripts/gui_tk_session.clj`); the live mode subscribes to the runtime,
  renders through `kmet.tk.*` and `app/ui/gui/*`, and binds the UI port.
  This is the proof the plan is written against.
- `rpc` (step 6): `kmet.modes.rpc`, EDN lines on stdin/stdout; the plan
  is in step 6.

## 5. Review findings (things the sketch got wrong or must fix)

**A. `:agent-settled` has no outcome.** `run-agent-turn` emits
`{:type :agent-settled}` with no payload (loop.clj:2950) and `cancel-turn`
emits only `:status :idle` (loop.clj:2960-2970). A headless consumer cannot
reliably distinguish completed / errored / cancelled settle from the stream
alone. This is partly solved one event earlier: `:agent-before-settle`
carries `:outcome @(:last-outcome agent)` (loop.clj:2694-2695) and reaches
the UI callback through `emit-boundary` (loop.clj:2207-2214), so
completed/error *are* visible; only a cancel skips the boundary
(`continue-prompt?` short-circuits on the signal), and the settle event
itself is always bare. Print mode sidesteps all of it with the
`:on-done`/`:on-error` callbacks, which is exactly the parallel API the
split should remove. **Required:** add `:outcome` to `:agent-settled` and
make the event-bus docs match. Two rules for the implementation:

- Reuse the existing vocabulary: `:completed | :error | :aborted`
  (loop.clj:149; `turn-outcome`, loop.clj:2040-2046). The `:cancelled`
  spelling proposed earlier would be a second word for `:aborted` — exactly
  the drift §4.2 rule 6 warns against.
- The naive payload (`@(:last-outcome agent)`) is wrong on cancel:
  `cancel-turn` does not set it, so it would report the previous turn's
  outcome. Either `cancel-turn` records `:aborted` or the settle site
  derives the outcome from the signal.

Small, early, and unblocks every headless mode.

**B. Callbacks vs events.** `:on-text`/`:on-done`/`:on-error` are a second
API. Step 1 keeps them as derived sugar; a later step either deletes them or
implements them strictly on top of events. Do not let both grow.

**C. Session validation cost is unmeasured.** Default load currently skips
malformed lines cheaply (`parseable-line?`, session.clj:220).
Validate-every-entry at load could add noticeable startup time on large
sessions. Decide with a measurement (malli `m/validate` per entry on a
generated 10k-entry session): always-validate, validate-only-on-skip, or
debug-only. Target: < 100 ms for the 10k-entry session; above that, validate
the header always and the full entries only on skip (or behind `--debug`).
Record the number in this document.

**Measured (step 0).** Scratch probe `target/engine-probe/measure.clj`
(removed by `bb clean`), malli 0.20.1: a generated 10k-entry / 22.0 MiB
(22 523 KiB) session sampled from the local session corpus (mix: 4 988
tool / 4 847 assistant / 153 user / 12 compaction entries), validated with
a compiled representative `:multi` over the stored entry roles.

| Measurement | bb v1.13.226 | jolt v0.8.21-205 |
|---|---|---|
| `m/validate` every entry | 139.3 ms | 137.8 ms |
| `m/validate` header only | 0.0 ms | 0.0 ms |
| `explain` + `humanize` (one bad entry) | 2.2 ms | 2.2 ms |
| EDN-parse the same 10k lines (baseline) | 1 520 ms | 1 194 ms |
| per 1k entries: assistant / tool / user | 17.2 / 14.7 / 9.9 ms | 17.0 / 13.0 / 9.3 ms |

Both hosts land above the 100 ms target (1.4x) on this slow Termux device,
and the parse that already happens costs ~10x more. **Decision (the plan's
fallback): validate the header always (free); full-validate entries only on
skip or behind `--debug`.** Normal loads stay as lenient as today: malli runs
when a dropped line needs a diagnosis (line + `humanize`) and for every entry
under `--debug`, so a parsed-but-wrong-shape entry is reported in debug only.
The probe's schema is hand-written from the real entry shapes, not the final
one — re-measure when the real schema lands (same probe, swap in the schema).

**D. Session resolution stays in the mode.** `bootstrap` cannot own
`--resume`-opens-a-selector vs print-errors-on-no-match; only the mechanics
move down. The runtime takes a *resolved* session, and a `:session-resolver`
hook may cover the shared `--continue` path.

**E. Tool `:params` is a lossy mini-language.** `app/tools/tool.clj`
converts `{:type :string|:number|:boolean :description :optional?}` to JSON
schema by hand. Deriving provider JSON schema from malli is attractive
(runtime arg validation included) but must satisfy pi's strict-mode subset
enforced by `ai/constrained-sampling.clj` — needs a parity test before it is
worth doing. Treat as a follow-up, not part of the split.

**F. The theme/package coupling is a view concern.** `app/themes` are
loaded by `app/packages.clj` via `tui.theme`, and extension resource paths
are applied by `app/extensions.cljc` via `theme/load-themes-from-dir`
(extensions.cljc:1180). The runtime should own resource *discovery*;
parsing/rendering belongs to the front end. Same for `app/bash_executor`'s
ANSI stripping (move to `kmet.libs`) and `app/theme_controller` (move up).
`app/keybindings` is a similar call: keymap definitions are arguably app
policy, their formatting is TUI.

**G. Queue drops are indistinguishable from drains.** `cancel-turn` calls
`clear-queues!` (loop.clj:2988), which *does* emit
`{:type :queue-update :steering [] :follow-up []}` (loop.clj:1212-1222) —
nothing is silent. But the emission is identical to a normal drain that
delivered the messages, so a client (RPC) cannot tell its queued message
was dropped. Add a reason (`:reason :cancelled`, or a `:dropped` count) to
the cancel-path `:queue-update`, or carry the drop in the settle outcome's
payload. Decide with finding A's payload.

**Settled (step 0).** `clear-queues!` takes an optional reason;
`cancel-turn` passes it, so the drop emits
`{:type :queue-update :steering [] :follow-up [] :reason :aborted}` — a
drain that delivered the messages still emits the same event without
`:reason`. `:aborted` is the existing outcome word (finding A), not a
second `:cancelled` spelling.

## 6. Data contract inventory

### Tier 1 — boundaries with hand-rolled validation today

| Surface | Today | Schema buys |
|---|---|---|
| Session entries (`app/session.clj`) | EDNL lines; header `{:type :session :version 1 ...}`; malformed → skip with warning; torn-tail repair via `parseable-line?` | `:multi` over the `:role` union (≈15 roles); distinguishes "unparseable" from "wrong shape"; per-version dispatch makes `:version` load-bearing |
| Message / content blocks | shapes restated across loop, session, provider builders | Highest-value schema; shared by session persistence and, later, the wire; validate provider SSE parses in tests. New `kmet.ai.message` namespace (none exists today), reused upward |
| Config (`kmet.config`) | `load-edn` → nil on invalid; `invalid-compaction-setting!` throws for a few keys | `m/decode` defaults/coercion for sections already validated (`:retry`, `:loop-guard`, compaction, `:images`, timeouts). File stays forgiving and open |
| `auth.edn` | `valid-credential?` predicate (`ai/auth.clj`) | Drop-in, better errors |

Later, only if it deletes existing hand-rolled code (not part of the
split): custom themes (`validate-theme-data!`, private at
`tui/theme.clj:913`), model catalogs + manifest
(`model_gen/validate-committed!`), and `extension.edn` (checks in
`tasks/build.clj` and `app/extensions.cljc`).

### Tier 2 — contracts the split exposes

| Surface | Why |
|---|---|
| Session import | Reuses the session schema at the `:session-import/*` boundary |
| Wire commands/responses (when RPC lands, step 6) | Validate at the transport edge; derive command docs from the registry |
| Extension ABI return shapes (optional, later) | Boundary returns `{:entries [...] :continue bool}`, input hooks `{:action :handled|:transform}`, tool-call hooks `{:block true :reason}` / `{:args ...}`, after-tool-call `{:content ... :is-error}`, `resources-discover` `{:skill-paths [...]}`; a schema lets the runner report a malformed handler result at the boundary |

### Tier 3 — do not schema

- `AgentState`, `CoreState`, TUI/GUI component records (atoms, protocols,
  derived caches).
- View-model shapes (footer, dock, selectors).
- In-process events and the `make-agent-state` bootstrap map — shaped by
  code and tests; pi types its event union and schemas only the wire.
- Anything `map->Record` already enforces at construction.

### Validation policy

| Boundary | Policy |
|---|---|
| Session load | Validate (subject to finding C): header always, entries per the measured policy; failures report line + `malli.error/humanize` |
| Session append | Validate the single appended entry in tests; optional debug assert; never re-validate the file |
| Config load | Decode with defaults; throw only where the code throws today; unknown keys ignored |
| Events | EDN round-trip only (tests and the event-output encoder); no schema validation in the hot path |
| Wire edge (RPC, step 6) | Validate every command and response |
| Hot paths (`:message-update` per delta) | Never; if ever needed, memoized `m/validator` per type |

## 7. Staged plan

Each step is shippable alone and keeps the TUI working. Gates for every step:
`bb test-changed`, `bb test-ext-changed`, `bb lint-changed`,
`bb format-check-changed`; `bb test` / `bb lint` at step boundaries.

### Step 0 — contract harness (no behavior change)

- Add the EDN round-trip test and the event-vocabulary parity test
  (`event-types` / `app-event-types` / `loop-event-types` keysets agree).
- Add a headless conformance renderer: consumes the loop's events in a test
  scenario and renders plain text. Any view data not in the stream surfaces
  here.
- Drive scenarios through the existing test harness (`test/kmet/app/
  test_loop.clj` fake-provider pattern; `test/kmet/modes/test_print.clj`).
- Finding A decision + implementation (settle outcome) lands here or in
  step 1; it blocks print and every later front end.

Acceptance: a scripted prompt run emits a stream that the conformance
renderer fully consumes; every event round-trips through EDN.

### Step 1 — session runtime and bootstrap

- Add the runtime under `kmet.app.*` (provisional name
  `kmet.app.session-runtime`; operations per §4.1) wrapping
  `run-agent-turn`, `make-agent-state`, `cancel-turn`, the queue helpers,
  and `app.session` mechanics. It is moved code, not a parallel facade; if
  it does not shrink `modes/interactive`, the step failed.
- Add `bootstrap` with the single wiring map; print mode and interactive
  layout call it. Interactive keeps its own event handler as a subscriber.
- Subscriptions: `:on-event` becomes a subscriber list internally
  (extension bus untouched). Callbacks stay as sugar (finding B).
- Runtime-scoped extension wiring: `set-session!`/`set-context-sink!`
  become runtime lifecycle calls; `set-ui-registry!` gains a documented
  front-end-owned install point.

Acceptance: print mode and TUI both run through `bootstrap`; the duplicated
wiring blocks in `modes/print.clj` and `modes/interactive/layout.clj` are
gone; `test/kmet/app/test_interactive_ui.clj` and `test_print.clj` pass
unchanged.

**Landed**: `kmet.app.session-runtime` provides `bootstrap`, subscriber
dispatch, bounded `dispose!` and runtime-scoped extension wiring; print
and the TUI both construct the engine, and the layout keeps its handler
as one subscriber. The remaining §4.1 session ops (`new-session!`,
`resume!`, `fork!`, `switch-session!`) and turn submission stayed in the
mode until step 3 — they landed there, and the test-stub fallback to
`agent/run-agent-turn` in `turn/start-agent-run!` retired in the same
step (tests build engines through `kmet.test-utils`).

### Step 2 — port print mode

- Print mode commutes its callbacks to subscriptions where practical;
  `:on-done` semantics come from the settle outcome (finding A).
- Delete `session-for-run` duplication in favor of the runtime helper.
- Add the event-output variant: one consumer, `--mode text|json` (pi's
  `runPrintMode` trick). Keep EDN as the format; if `:message-update`
  snapshots prove heavy, add a single `wire-event` projection function
  (pi's `toJsonEvent`: strip cumulative snapshots, keep usage and tool-call
  id/name) and test it.

Acceptance: print mode has no direct `make-agent-state` call; all
`test_print` cases pass; a scenario piped through the event output emits
every `loop-event-types` event and each round-trips through EDN.

**Landed**: print mode is a pure event-stream consumer — text mode streams
`:message-update` text deltas and reports failure from the `:agent-settled`
outcome (`:error` / `:aborted` → stderr, exit 1), and `--mode edn` (implies
`--print`) writes the session header plus one EDN map per event, with
`event-bus/wire-event` dropping the cumulative `:message-update` snapshot
and stringifying error payloads (`wire-error`).
The session-directory helpers (`ensure-cwd-session-dir`,
`find-recent-session`) moved from the mode to the runtime, leaving
`session-for-run` as policy only.

### Step 3 — move session/turn ownership under the runtime

- Move `modes/interactive/session_admin.clj` switching/creation and
  `turn.clj` submission (input hooks, skill/prompt expansion, queueing) into
  the runtime; modes keep selectors, rendering, and UX policy.
- Split slash commands by capability: engine work through the runtime,
  dialogs through the UI port, view-only commands staying in the mode.
  `app.commands` handlers currently take `CoreState`; introduce a
  `[ctx args]` handler contract and migrate in place (decided in §10).
- `CoreState` shrinks to view state + runtime reference.

Acceptance: `/new`, `/resume`, `/fork`, `/tree`, `/compact`, `/model`,
submit/steer/cancel work with the mode holding only view state; the TUI test
suite passes.

**Landed.** The runtime owns the session lifecycle (new-session!, resume!,
fork!, clone!, switch-session!, branch-session!), the submission chain
(input hooks, skill/prompt expansion, steer-or-start routing), the
compaction queue (queue/clear/flush), the turn flag and the runtime cwd;
`session_admin` and `turn` keep the UI reactions (selectors, replay,
editor/pending/status presentation); `session_admin.clj` fell from 960 to
799 lines and `turn.clj` from 796 to 663, while the runtime grew from 336
to 847.
`CoreState` now holds view state plus `:engine` only — the agent state,
session atom, turn flag, compaction queue and config are read through the
runtime accessors. Slash commands dispatch with the ctx shape
(`{:runtime :ui :frontend}`, still carrying the legacy keys until the UI
port lands in step 4) and builtins register with the `:interactive`
front-end tag, which dispatch and autocomplete filter by.
Two deviations from the sketch, both deliberate: the compaction-queue flush
receives the front end's run callbacks in its call (the runtime does not
hold view callbacks), and engine-portable command *implementations* have
not moved to new homes yet — they call runtime operations, but the split
into `kmet.app.*` homes waits for the step 4 UI port, when a handler can
run without a CoreState.

### Step 4 — extension UI port + request/response

- Define the UI port capability map and the `:ui-request`/`respond!` pair.
  Bind it through a `bind-extensions`-style call that passes the front-end
  kind (pi: `bindExtensions({uiContext, mode, commandContextActions,
  abortHandler, shutdownHandler, onError})`), so extensions can branch on
  `ctx.mode` / `ctx.has-ui`.
- Interactive implements it as today (possibly via an in-process promise);
  print installs auto-answers; the global registry gains runtime-scoped
  install/clear. A future RPC mode answers asynchronously with `respond!`.
- Document the degraded set for front ends without a terminal (pi's RPC
  table: dialog methods work, TUI-only methods become no-ops, dialog
  timeouts auto-resolve).

Acceptance: extensions using `ctx.ui` still pass `test/kmet/app/
test_extensions_ui.clj`; a print-mode extension dialog test answers with the
default; the GUI can install its port.

**Landed (the port seam).** `extensions/bind-extensions!` installs the
front-end kind + capability map; `unbind-extensions!` restores the headless
defaults. `bootstrap` binds `:print`/`:json` (print mode passes its output
variant), the interactive registry binds `:interactive`/has-ui true, and
`dispose!` unbinds. `default-extension-context` reads the bound kind, so
`ctx.mode`/`ctx.has-ui` are correct per front end. `bootstrap`'s headless
port is `extensions/headless-ui-port` (pi's `noOpUIContext`): inert setters,
neutral getters, `:set-theme` failure, `ui-custom` → resolved nil promise,
and `:request` auto-answers (`:select` nil, `:confirm` false,
`:input`/`:editor`/`:custom` nil). The runtime adds `request-ui!` and
`respond!`: a port answers in-process (value, or `extensions/request-pending`
when its dialog resolves later) or forwards the request and answers with
`respond!`; `dispose!` resolves anything still pending with the request's
degraded default. `build-extension-context` takes `:mode`/`:has-ui` from the
binding, so a front end's `:build-context` cannot drift the kind. Covered by
`test/kmet/app/test_ui_port.clj`; `extension.md` documents the degraded set
and the request protocol.

**Runtime capabilities (pi: bindCore).** The agent-control half of the
capability map moved to the runtime: `bind-runtime-capabilities!` installs
`set-model`, `get/set-thinking-level`, `send-user-message`,
`send-message!`, `get/set-active-tools` from
`session-runtime/runtime-capabilities` at `bootstrap` and clears them at
`dispose!`. The extension api dispatches those through
`extensions/runtime-call`, so they work headless (print/json) instead of
silently no-oping; `register-shortcut!` stays front-end-only. The
interactive registry dropped its copies — footer/editor-border view
updates ride the `:model-select`/`:thinking-level-select` events, and
runtime-started runs present through `set-run-callbacks!` (the TUI installs
`turn/run-callbacks`, so an extension send streams and tears down like an
editor submission).

**Command ctx (the consumer).** Command handlers now receive the real
`{:runtime :ui :frontend}`: `:runtime` is the engine, `:ui` the front end's
command-UI value, and no legacy CoreState keys. The interactive value
(`turn/interactive-command-ui`) carries the shared output capabilities
(`:message`, `:info`, `:warning`, `:status`, `:notify`, `:set-title`) plus
`:state`, the CoreState its own view-bound commands read; the interactive
registration passes `:state` to those handlers, so their bodies are
unchanged. `kmet.app.commands.builtins` registers the engine-portable
commands (`/continue`, `/name`, `/export`, `/compact`) untagged, so any
front end can dispatch them; they use only `:runtime` and the shared UI
capabilities (async paths included — `/compact` replies from its future
through `:info`). `parse-path-argument` moved to `kmet.app.session-export`
so the portable `/export` needs no mode code. The remaining commands are
view-bound (`:frontend :interactive`) and stay in the mode.

### Step 5 — GUI mode (the proof)

The backend already exists — `src/kmet/tk/` ships the Tk/Tcl backend
(`kmet.tk.core`, `kmet.tk.ttk`, `kmet.tk.dsl`, `kmet.tk.options`,
`kmet.tk.tcl`) with its own `README.md`, and the display-only viewer
(`scripts/gui_tk_session.clj` + `app/ui/gui/transcript.clj`) already
renders a saved session. What is missing is the live run:

- Subscribe to the runtime, marshal to the toolkit thread, and drive
  prompt/stream/cancel through the same operations the TUI uses.
- The mode constructs no `AgentState`, no session atoms, and no
  `make-agent-state` map; everything comes from the runtime and the stream.
- Reuse `app/ui/gui/*` for rendering; the transcript's TUI-renderer reuse
  is intentional and stays.

Acceptance: the viewer becomes live — prompt, streamed assistant text, tool
cards, and cancel — with the mode holding only view state and the runtime
reference; every update it renders arrives through `subscribe!`.

### Step 6 — RPC mode (scheduled; GUI postponed)

Step 5 is postponed until the Tk backend stabilizes (parallel development).
RPC is the next front end: it exercises the step 4 seam from the side the
GUI cannot yet reach — an out-of-process consumer whose extension dialogs
are **answered asynchronously** (§4.5, extension.md "UI requests") — and
it needs no terminal. Reference: pi `modes/rpc/*.ts`, `docs/rpc.md`,
`docs/rpc-commands.md`, `docs/json.md`, `docs/rpc-extension-ui.md`.

The runtime is already the right shape for this: `bootstrap` builds one
engine, `subscribe!` broadcasts the event stream, `submit!`/`steer!`/
`follow-up!`/`cancel!`/`compact!` drive it, `switch-session!`/
`new-session!`/`fork!`/`clone!`/`entries` own the session, and
`request-ui!`/`respond!` are the async answer pair. RPC is a transport plus
a dispatch table over those; almost no new engine code is expected. Two
small runtime additions are known: a bounded wait-for-idle (pi's `abort`
waits for the session to settle before responding) and live queue-mode
setters (the loop owns `:steering-mode`/`:follow-up-mode` in its `:cfg`
snapshot; nothing sets them after bootstrap today).

#### 6.0 Pinned decisions

- **Transport**: EDN, one map per line (LF-framed), commands on stdin,
  records on stdout. stdout carries protocol only — `kmet.debug` already
  writes files, so no output guard is needed. `run` takes `:in`/`:out`
  overrides (Reader/Writer) so tests drive the protocol in-process.
- **CLI**: `--mode rpc` (`parse-args` already anticipates pi's
  `text|json|rpc`; kmet's `:edn` is pi's `json`). Session resolution is
  the same policy print mode uses (`--session`/`--continue`/fresh); print's
  private `session-for-run` moves to a shared app home rather than being
  copied.
- **Binding**: `{:mode :rpc :has-ui true :ui rpc-port}` — pi sets
  `ctx.mode === "rpc"` and `hasUI === true` because dialogs and output
  channels work over the protocol. TUI-only surfaces are absent (inert) or
  degrade per pi's `rpc-extension-ui.md` limitations table; the list is
  documented.
- **No `subscribe` command** (the sketch had one): every client receives
  the event stream, pi-style. One process, one engine, one session at a
  time; session commands swap the engine's session and the events report
  it.
- **Records**:
  - command `{:type :prompt :id "req-1" :message "..."}` — `:id` is an
    optional string, correlated by the client, never by order;
  - response `{:type :response :id "req-1" :command :prompt :success
    true :data {...}}`, failures `:success false :error "..."`;
  - a malformed line answers `{:type :response :command :parse :success
    false :error "..."}` with no id (pi discipline);
  - events are the print-mode wire projection, `(wire-event e)` — the
    same `{:type ...}` family, distinguishable from responses by `:type`;
  - extension UI: the port emits
    `{:type :ui-request :id "uuid" :method :select ...}` records
    (dialogs awaited, fire-and-forget ones not) and the client answers
    dialogs with `{:type :respond :id "uuid" :value ...}` →
    `runtime/respond!`. A client that never answers gets the request's
    default when `dispose!` runs; the port owns any timeout policy
    (extension.md).
- **Discipline** (copied from pi's docs): accepted is not completed — a
  `prompt` response reports the disposition (`started`/`queued`/
  `handled`) and the client waits for `:agent-settled`; a command failure
  is a response, a run failure is an event; stdin EOF requests shutdown
  and `dispose!` runs after the settle.
- **Validation at the edge** (§9 Tier 2: "validate every command and
  response"): the transport validates one command against its table row
  (missing/mistyped fields → an error response, never a crash), and the
  malli schemas land once the surface is complete — at the single read
  point and the single encoder, never in the event hot path (Tier 3).

#### 6.1 Command surface (pi names; kmet mapping)

| pi command | kmet |
|---|---|
| `prompt` | portable builtin dispatch (6.3) else `runtime/submit!` with `:expand?`; disposition from its action map |
| `steer`, `follow_up` | skill/template expansion then `runtime/steer!`/`follow-up!`; `{:disposition :queued}` |
| `abort` | `runtime/cancel!` then bounded wait for idle (the known runtime addition) |
| `clear_queue` | `runtime/clear-queues!` + report the dropped texts |
| `new_session` | `runtime/new-session!` (`{:session ...}`/`{:cancelled true}`) |
| `get_state` | `runtime/snapshot` + session file/name/counts |
| `get_messages`, `get_last_assistant_text` | session accessors |
| `set_model`, `cycle_model`, `get_available_models` | runtime capability + model catalog |
| `set_thinking_level`, `cycle_thinking_level`, `get_available_thinking_levels` | runtime/loop thinking API |
| `set_steering_mode`, `set_follow_up_mode` | needs the live loop setter (the other known addition) |
| `compact`, `set_auto_compaction` | `runtime/compact!` (accept → response, outcome → events); `agent/set-auto-compact!` exists |
| `get_session_stats`, `export_html` | `session/get-session-stats`, `session-export/export-to-html!` |
| `switch_session`, `fork`, `clone`, `get_entries`, `get_tree` | `runtime/switch-session!`/`fork!`/`clone!`, `session/get-tree` |
| `set_session_name` | the `/name` builtin's shared logic (one implementation) |
| `get_commands` | the registry filtered to `:rpc` availability + skills/prompts |
| `bash`, `abort_bash`, `set_auto_retry`, `abort_retry` | **deferred**: kmet's bash path is the interactive `!` flow, and the retry knobs have no live setter |

**Known parity deltas** (documented for clients in 6d): a running `abort`
drops the steering/follow-up queues (kmet's cancel semantics persist the
drop as an `:aborted` queue-update) where pi continues them — the pi
recipe applies: send `clear-queue` first to capture the texts (an idle
abort is a no-op); `steer`/`follow-up` do not
run input hooks (kmet's hooks live in `submit!`) and reject `:images` until
the queues carry image blocks; `get-state`'s model is the id + provider,
not a full object (`get-available-models` fills that in); commands are
served serially (a blocking `abort`/`new-session` delays the next line);
no SIGTERM/SIGHUP handlers yet (the session file is appended as entries
stream, so an exit loses nothing).

#### 6.2 The step 4 seam's first consumer (extension UI)

The RPC port is the first thing that answers the `:request` protocol:

- dialogs: the runtime's `{:kind :select|:confirm|:input|:editor}` request
  becomes a `:method`-carrying `:ui-request` record; the port returns
  `extensions/request-pending` and the `:respond` command resolves it via
  `runtime/respond!`. `:custom` is not bridged (pi limitation).
- output channels: the extension API's `notify`, `set-status`,
  `set-widget`, `set-title`, `chat-info`, `set-editor-text` emit
  fire-and-forget `:ui-request` records; TUI-only capabilities stay
  inert/degraded and are listed in the docs.
- extension ctx: commands and event handlers see `:mode :rpc`,
  `:has-ui true`.
- extension **dialog API methods** (`ctx.ui.select` etc.) still have no
  built-in caller (extension.md); wiring this port does not require them.
  Tests drive `request-ui!` directly for the round trip; adding the API
  methods is a separate follow-up the port is already shaped for.

#### 6.3 Portable commands over `prompt`

A prompt that reads as `/<name>` dispatches through
`commands/find-command name :rpc` with the command ctx
`{:runtime engine :ui rpc-command-ui :frontend :rpc}` — the step 4b payoff:
`/continue`, `/name`, `/export`, `/compact` work from an RPC client, while
`:interactive`-tagged view commands are filtered out. Unknown slash text
stays a normal prompt (pi's `prompt` handles known commands and sends the
rest to the model). `get_commands` reports this same filtered view.

#### 6.4 Staging

- **6a — transport + prompting**: `kmet.modes.rpc` namespace, `--mode rpc`,
  line loop, dispatch table, responses, event forwarding, `prompt`,
  `steer`, `follow_up`, `abort`, `clear_queue`, `new_session`,
  `get_state`; in-process tests with the stub-turn pattern print mode uses.
- **6b — the command surface**: session/model/thinking/queue-mode/stats/
  export/tree commands, `get_commands`; the two runtime additions land
  here; per-area tests.
- **6c — extension UI + portable `prompt` commands**: the port, the
  `:respond` command, degraded-capability docs, round-trip tests; an
  extension-output test (`ui-notify`/`ui-set-status`).
- **6d — client, docs, end-to-end**: a thin EDN client
  (`kmet.modes.rpc-client`: send + await id, event callback, wait-idle)
  used by the `^:slow` subprocess round-trip test and documented as the
  scripted-consumer example; `docs/rpc.md` user guide + supported-command
  reference; fold this note into `src/kmet/README.md` as steps land.

Acceptance: protocol-discipline tests green (ids optional, malformed →
parse response without id, stdout protocol-only, accepted ≠ completed,
stdin EOF → dispose after settle); a `--mode rpc` prompt streams events and
settles; extension output records and a `request-ui!` → `:respond` round
trip work, and an unanswered request resolves with its default at dispose;
the namespace graph loads no `kmet.tui.*` (extend the §8 assertion); the
portable builtins are dispatchable over `prompt`; the pi parity table
(supported vs deferred) is in the docs.

**Landed (6a)** — `kmet.modes.rpc` (records, dispatch table, line loop,
event forwarding; `prompt`, `steer`, `follow-up`, `abort`, `clear-queue`,
`new-session`, `get-state`), `--mode rpc` (`run-non-interactive-mode!`),
`test/kmet/modes/test_rpc.clj` (11 tests). Two shared pieces landed with
it: print's session resolution moved to the runtime as
`resolve-run-session` (both modes call it), and `wait-idle!` (the abort
discipline). The engine binds `:mode :rpc` but `:has-ui false` until 6c
wires the port — the headless defaults are the honest degraded surface
until then.

## 8. Enforcement

- **Layer guard** modeled on `test/kmet/libs/test_self_contained.clj`
  (which reads the `ns` form, not a regex): every `src/kmet/app/**` file —
  excluding `app/ui/**` (app-specific view code) — must not require `kmet.tui.*`,
  `kmet.tk.*`, or `kmet.modes.*`. Seed the allowlist with the six known
  violations (§3.6: `bash_executor`, `theme_controller`, `packages`,
  `keybindings`, `extensions.cljc`, `extensions/context.cljc`) and shrink
  it as they are fixed.
- **Headless guard caveat.** A `require`-graph assertion that *no*
  `kmet.tui.*` namespace loads is not achievable while extensions are
  enabled: the extension ABI admits `kmet.tui.*` and `host-requires!`
  requires the whole layer on the first extension (§3.8). Assert instead
  that a headless front end (RPC mode) does not load
  `kmet.modes.interactive.*` and does not construct a terminal; the
  stricter assertion needs the shared-component-library split (an
  unscheduled extraction). The GUI is not under this guard — `kmet.tk.*`
  plus the deliberate TUI-renderer reuse is its intended stack.
- **Vocabulary parity**: `event-types`, `app-event-types`, and
  `loop-event-types` keysets agree (test).
- **EDN contract**: recorded scenarios round-trip; the conformance renderer
  consumes every `loop-event-types` event with no internal-state access.
- **Docs**: update `src/kmet/README.md` (layer map, state conventions),
  `src/kmet/extension.md` (UI port, async UI requests), and
  `src/kmet/app/event_bus.clj` descriptions in the change that alters them.
  This file is listed among the temporary root notes in `AGENTS.md` while
  it exists.

## 9. Dependency and portability notes

malli probes (scratch dirs under `target/`, removed by `bb clean`):

| Probe (re-verified 2026-10-14) | bb v1.13.226 | jolt v0.8.21-184-gd17f10f9 |
|---|---|---|
| `malli.core` require, validate, `:multi`, `m/explain` + `humanize` (0.19.1 and 0.20.1) | ✅ | ✅ |
| `malli.transform` string transformer | ✅ | ✅ only with the existing `io.github.jolt-lang/time` dep (without it: `No matching ctor found for class DateTimeFormatterBuilder`) |
| unknown `:type` rejected by `:multi` | ✅ | ✅ |

- malli is **not bundled** by either host (checked). Add it to `deps.edn`
  and `bb.edn` and pin one version (0.20.1 was verified; re-verify with
  `bb check`, `bb lint` both reader views, and a `jolt -e` require probe)
  **in the step that lands the first boundary schema** (session load), not
  now.
- Prefer `malli.core` + `malli.error` only. `malli.transform` is optional;
  if used, it rides on the already-pinned `jolt-lang/time`.
- malli is not added to the fixed extension set (`kmet.app.extension-libs`);
  extensions do not get it unless that decision is made deliberately. If an
  extension-facing validator is wanted, `kmet.extension` can re-export a
  small `validate` helper later.

## 10. Deferred questions

Multi-engine scoping (§1, §4.5) and command split granularity are settled.
The rest are parked with the step that resolves them.

1. **Settle outcome shape (finding A)** — done in step 0 (`:agent-settled`
   carries the existing `:completed | :error | :aborted` vocabulary; the
   cancel queue drop carries `:reason :aborted` on the cancel-path
   `:queue-update` — findings A and G).
2. **Session load validation policy (finding C)** — measured in step 0:
   139 ms (bb) / 138 ms (jolt) per 10k entries, above target; header
   always, entries only on skip or `--debug`.
3. **Command split granularity** — settled and landed. Handlers receive
`{:runtime :ui :frontend}` (no legacy keys); the engine-portable builtins
(`/continue`, `/name`, `/export`, `/compact`) live in
`kmet.app.commands.builtins` behind the shared command-UI capabilities,
and the view-bound commands stay in the mode behind `:frontend
:interactive`. One handler shape, no intent language: a handler acts
through the runtime and the UI value (the `{:message s}`/`{:stop true}`
result sketch was dropped — a capability map serves sync and async output
alike). Extension commands keep the same `ctx` shape. Intents/effects would
only pay off for a front end that executes commands in another process; a
future wire mode should instead expose the operations as protocol commands
(pi's `new_session`/`fork`/…), not serialize slash-command intents.
4. **`:resume` selector** — step 3.
5. **GUI scope** — step 5.
6. **RPC trigger** — scheduled; the GUI is postponed (step 6).

## 11. What not to do

- Do not rename `kmet.app.*`. The split is ownership, not naming; a
  rename stage buys nothing and inflates the diff.
- Do not build RPC before its transport is worth it. The plan is step 6;
  print's event-output variant already proves headless conformance, and the
  GUI's live milestone stays parked until the Tk backend stabilizes.
- Do not share TUI component *instances* with the GUI; share the renderers,
  events, and data. The GUI transcript reusing the TUI markdown/theme/ANSI
  output is intended — it converts ANSI to Tk segments (see §2).
- Do not close payload maps or validate the hot path.
- Do not schema in-process events, the bootstrap map, or view models —
  type them and test them. Schema the files now and the wire if it exists.
- Do not let `:on-text`/`:on-done` callbacks and the event stream grow as
  two independent APIs.
- Do not add extension-scoped exotic schemas (tool params, provider JSON
  schema derivation) before the core contract is stable — finding E.
