# Engine/UI split — working plan

Status: working design note at the repository root (like `run_code.md`,
`perf.md`, `windows.md`). It records decisions, a staged plan, and open
questions. It is not user documentation and it is not authoritative for
behavior — the topic map in `AGENTS.md` still points at the behavior docs.
Fold this into `src/kmet/README.md` (or delete it) when the work lands.

Scope: one agent engine usable unchanged by the interactive TUI, print mode,
a future RPC mode, and a future GUI. Namespaces are **not renamed** while the
seam is built; a rename stage is described at the end but is optional and
mechanical.

Decisions already taken in discussion:

- **EDN is the event format** — in-process and on the wire. No JSON
  projection; the internal shape and the wire shape are one thing.
- **malli** is the schema tool for data contracts.
- Engine work precedes renames.

---

## 1. Goal and non-goals

Goal: any front end can drive a session through one facade and one event
stream without touching loop, session, tools, provider, or extension
internals.

Non-goals (v1):

- **Multi-engine processes.** One engine per process. The extension runtime
  (`kmet.app.extensions` globals) is process-global; the facade wraps it so
  per-engine scoping stays possible later, but nothing in this plan requires
  it.
- **Sharing view code between front ends.** TUI components, `app/ui/*`, and
  GUI widgets stay per-front-end. What is shared is the engine, the event
  stream, and the data providers.
- **A distributed/network protocol.** RPC is stdin/stdout EDN lines first.

## 2. Baseline: what already separates

The seam mostly exists today; the plan sharpens it rather than inventing it.

- `kmet.app.loop` (3.2k lines) is UI-free and emits a documented event
  vocabulary (`kmet.app.event-bus`): `event-types`, plus the derived
  `app-event-types` / `loop-event-types` sets. `emit` routes each event to
  the single `:on-event` callback and to `event-bus/emit-event!`
  (extensions).
- `kmet.modes.print` drives the loop headlessly with no TUI — the proof that
  a second front end is viable.
- `kmet.ai.*` is a standalone provider/auth library; `kmet.app.tools/*`
  contain no TUI requires.
- `app/ui/*` (~10k lines) is already fenced as app-specific presentation,
  and the layer rules in `src/kmet/README.md` state the direction
  (`libs` → `tui`/`gui` → `app` → `modes`).

## 3. Findings: what actually blocks the split

1. **No engine bootstrap.** `make-agent-state` wiring is duplicated in
   `modes/print.clj` and `modes/interactive/layout.clj`: system prompt,
   default tools, retry/loop-guard settings, HTTP timeouts, scoped models,
   extension session/context wiring. Every new mode copies 50+ lines and can
   silently miss a key (the print-mode comments about
   `get-with-default` vs `get` document exactly such a near-miss).
2. **No session owner.** Resume/fork/tree/import live in
   `modes/interactive/session_admin.clj`; submit/steer/cancel/queue lives in
   `modes/interactive/turn.clj`; session-dir helpers plus a global config
   atom live in `modes/interactive/state.clj`. These are engine behavior.
3. **One `:on-event` callback, no subscription.** `AgentState` holds a
   single callback; a GUI, RPC client, and automation listener cannot
   coexist, and there is no unsubscribe.
4. **Engine state and view state share one record.** `CoreState` holds
   `agent-state`, `session-atom`, `running-turn?` next to editors, docks,
   footers, and selectors.
5. **Extension runtime is process-global atoms** (`app/extensions.cljc`:
   `ui-registry`, `session-atom`, `context-sink-atom`). Print mode stubs a
   UI registry to satisfy the same globals.
6. **Four engine-adjacent namespaces require TUI**:
   `app/bash_executor.clj` (`tui.utils`), `app/theme_controller.clj`,
   `app/packages.clj` (`tui.theme`), `app/keybindings.clj`
   (`tui.keybindings`). Small, but they are the ones that would drag the
   terminal into a GUI process.
7. **No serialization contract.** Events carry partial message snapshots;
   session files are read leniently (malformed line → skip with a warning);
   nothing asserts that an emitted event is plain EDN data.

## 4. Target architecture

```
                    kmet.modes.interactive ─┐
                    kmet.modes.print       ─┤   front ends: input, rendering,
                    kmet.modes.rpc         ─┼─  session UX, extension UI port
                    kmet.modes.gui         ─┘
                                │ subscribe / prompt! / respond!
                    ┌───────────┴────────────┐
                    │      kmet.engine       │  facade: session, prompts,
                    │  (kmet.app.* today)    │  commands, extensions, schema
                    └───────────┬────────────┘
             kmet.ai (provider lib) · kmet.app.tools · kmet.libs.*
                                │
                    kmet.tui.* / kmet.gui.* (per-front-end view backends)
```

Dependency rule (enforced by a test, §8): the engine package may require
`kmet.libs.*` and `kmet.ai.*`, and **never** `kmet.tui.*`, `kmet.gui.*`,
`kmet.modes.*`, or `kmet.app.ui.*`. While `kmet.app.ui` still lives inside
`kmet.app`, the guard excludes that subtree explicitly.

### 4.1 Engine facade

The facade owns a **session runtime**: `AgentState`, the `Session` record,
the config, the extension runtime handle, and subscribers. One constructor,
one lifecycle.

```clojure
;; kmet.engine (namespace name is stage-R; function names are the contract)
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
(last-outcome engine)       ; {:outcome :completed|:error|:cancelled}
(respond! engine id value)  ; answers a :ui-request (4.5)
```

Rules:

- `prompt!` returns immediately. Completion is observed through
  `:agent-settled` (§4.2), not through a return value; a mode that needs
  blocking (`print` / `rpc` one-shots) uses its own promise.
- `bootstrap` takes the **resolved** session or a resolution request. Session
  *policy* stays per mode: `--resume` opens the TUI selector, print mode
  errors on no match, RPC takes an explicit path. `app.session` already
  provides the mechanics (`resolve-session-path`,
  `find-most-recent-session`, `session-dir-for-cwd`); the engine adds the
  helpers that today live in `modes/interactive/state.clj`
  (`ensure-cwd-session-dir`, `runtime-cwd`, `find-session`).
- The facade wraps `run-agent-turn` unchanged in step 1. `:on-text`,
  `:on-thinking`, `:on-done`, `:on-error` stay as sugar **derived from the
  same emission path**, so they cannot drift from the event stream (print
  mode keeps working while it migrates).
- `snapshot` returns plain data; it may mix per-field atom values (the agent
  uses independent atoms). Display-only consumers are fine with that;
  anything needing a consistent cut must document it.

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
3. **Subscribers never block the engine.** If async buffering or coalescing
   is ever needed (high-rate `:message-update`), it is per-subscriber and
   opt-in, not a global queue in the loop.
4. **Extension handlers are different.** They may block (a dialog waits for
   a user, an RPC client answer waits on a promise) — that matches TUI
   semantics today. Only *subscribers* have the non-blocking rule.
5. **The stream is the whole truth for a front end.** If a view needs data
   that is not in the stream, the event gains a field rather than the view
   reaching into `AgentState`. A headless renderer test (§8) enforces this.
6. **Fully-qualified keywords for wire-relevant keys.** Event `:type` and
   value keywords stay unqualified (`:assistant`, `:completed`); payload
   keys are plain keys. Namespaced keys should be reserved for stable
   extension payloads.

### 4.3 Data schemas (malli)

malli schemas describe values that **cross a boundary or outlive the
process**. Schemas are data, keyed by type/role/version in explicit
registries next to the format they describe.

```clojure
;; kmet.engine.event-schema
(def event-schemas
  {:agent-start [:map {:description "..."} [:type [:= :agent-start]]]
   :message-update
   [:map [:type [:= :message-update]]
    [:message :kmet.ai.message/assistant]
    [:delta [:map [:type [:enum :text :thinking :tool-call]]]]]
   ;; ...
   })

(def Event (into [:multi {:dispatch :type}] event-schemas))
```

- Payload maps are **open** by default; extensions attach keys. `:closed
  true` only where an extra key is a bug.
- Unions are derived from the registries (`:multi`, per-version dispatch)
  so adding a type/role is one entry. A keyset-parity test keeps the schema
  map and the vocabulary/`event-types` map from drifting.
- Prose stays in the docs; schema maps carry `:description` and a coverage
  test asserts every schema has one.

### 4.4 Session and state ownership

- `Session` (record with `:header`, `:entries` atom, `:leaf-id` atom, lock)
  stays in `kmet.app.session`; the engine owns its lifecycle. The mode gets
  it via `(snapshot engine)` and listens for session events.
- Session-dir helpers move out of `modes/interactive/state.clj` into the
  engine (or into `kmet.app.session`), with config injected rather than read
  from a mode-global atom.
- `AgentState`, `CoreState`, and TUI component records are **not** schema
  targets. Their contract is the facade API and tests.
- Session switching keeps its two-phase contract: before-events
  (`:session-before-switch`, `:session-before-fork`, `:session-before-tree`)
  are emitted by the engine and may be cancelled by extensions; the swap
  happens only when no handler cancels. The mode owns the UX around it
  (selectors), not the swap.

### 4.5 Extension runtime and the UI port

Extensions are loaded by the process, but their UI surfaces belong to the
active front end. Split the capability map:

- **Engine capabilities** (session facade, context sink, tool hooks, event
  bus, command/tool/renderer registries): engine-scoped, installed by
  `bootstrap`, cleared by `dispose!`.
- **UI port** (`ExtensionUIContext`: widgets, dialogs, editor, statuses):
  installed by the front end. `modes/interactive/ui-registry.clj` is the TUI
  implementation; print mode installs auto-answer defaults; RPC forwards
  requests to the client; GUI builds its own.
- **Async UI requests.** Synchronous dialogs cannot cross a socket. The
  engine-level contract is a request/response pair of events:
  `{:type :ui-request :id ... :kind :select|:confirm|:input|:editor ...}`
  answered by `(respond! engine id value)`. TUI implementations may still
  answer synchronously (the request emission can resolve an in-process
  promise); RPC answers when the client responds.
- Extension reload semantics (`ui-reset!`, `clear-runtime!`,
  `session-shutdown` before unload) stay as they are; the facade exposes the
  lifecycle instead of the mode calling the globals directly.

### 4.6 Modes

- `interactive`: subscribes for rendering; `CoreState` shrinks to view state
  plus the engine reference; `turn.clj`/`session_admin.clj` logic moves
  under the engine, leaving the handlers and widgets.
- `print`: migrates in step 2 to prove the seam (smallest consumer). Keeps
  its `:on-done` semantics via the settled outcome (§5, finding A).
- `rpc` (new): `kmet.modes.rpc`, EDN lines on stdin/stdout. Commands in with
  an `:id`; responses correlated; engine events out as-is. Extension UI
  requests are forwarded and answered with `respond!`.
- `gui` (new): subscribes and renders through `kmet.gui.*`; marshals to the
  toolkit thread.

## 5. Review findings (things the sketch got wrong or must fix)

**A. `:agent-settled` has no outcome.** `run-agent-turn` emits
`{:type :agent-settled}` with no payload (loop.clj ~2950) and `cancel-turn`
emits only `:status :idle`. A headless consumer cannot reliably distinguish
completed / errored / cancelled settle from the stream alone. Print mode
sidesteps this with the `:on-done`/`:on-error` callbacks, which is exactly
the parallel API the split should remove. **Required:** add an outcome to
`:agent-settled` (e.g. `{:outcome :completed|:error|:cancelled}`) or emit a
dedicated terminal event, and make the event-bus docs match. Small, early,
and unblocks every headless mode.

**B. Callbacks vs events.** `:on-text`/`:on-done`/`:on-error` are a second
API. Step 1 keeps them as derived sugar; a later step either deletes them or
implements them strictly on top of events. Do not let both grow.

**C. Session validation cost is unmeasured.** Default load currently skips
malformed lines cheaply. Validate-every-entry at load could add noticeable
startup time on large sessions. Decide with a measurement (malli
`m/validate` per entry on a generated 10k-entry session): always-validate,
validate-only-on-skip, or debug-only. Record the number in this document.

**D. Session resolution stays in the mode.** `bootstrap` cannot own
`--resume`-opens-a-selector vs print-errors-on-no-match; only the mechanics
move down. The facade takes a *resolved* session, and a `:session-resolver`
hook may cover the shared `--continue` path.

**E. Tool `:params` is a lossy mini-language.** `app/tools/tool.clj`
converts `{:type :string|:number|:boolean :description :optional?}` to JSON
schema by hand. Deriving provider JSON schema from malli is attractive
(runtime arg validation included) but must satisfy pi's strict-mode subset
enforced by `ai/constrained-sampling.clj` — needs a parity test before it is
worth doing. Treat as a follow-up, not part of the split.

**F. The theme/package coupling is a view concern.** `app/themes` are
loaded by `app/packages.clj` via `tui.theme`. The engine should own resource
*discovery*; parsing/rendering belongs to the front end. Same for
`app/bash_executor`'s ANSI stripping (move to `kmet.libs`) and
`app/theme_controller` (move up). `app/keybindings` is a similar call:
keymap definitions are arguably app policy, their formatting is TUI.

**G. `:agent-settled` and queue semantics on cancel.** `cancel-turn` clears
steering/follow-up queues silently. A client (RPC) needs to know its queued
message was dropped; either the queue-update event is emitted on cancel or
the outcome event carries it. Add to finding A's payload decision.

## 6. Data contract inventory

### Tier 1 — boundaries with hand-rolled validation today

| Surface | Today | Schema buys |
|---|---|---|
| Session entries (`app/session.clj`) | EDNL lines; header `{:type :session :version 1 ...}`; malformed → skip with warning; torn-tail repair via `parseable-line?` | `:multi` over the `:role` union (≈15 roles); distinguishes "unparseable" from "wrong shape"; per-version dispatch makes `:version` load-bearing |
| Message / content blocks | shapes restated across loop, session, provider builders | Highest-value schema; shared by events, persistence, wire; validate provider SSE parses in tests. Lives in `kmet.ai`, reused upward |
| Config (`kmet.config`) | `load-edn` → nil on invalid; `invalid-compaction-setting!` throws for a few keys | `m/decode` defaults/coercion for sections already validated (`:retry`, `:loop-guard`, compaction, `:images`, timeouts). File stays forgiving and open |
| `auth.edn` | `valid-credential?` predicate (`ai/auth.clj`) | Drop-in, better errors |
| Custom themes (`tui/theme.clj`) | `validate-theme-data!` | User-authored files; token types/required keys as data |
| Model catalogs + manifest | `model_gen/validate-committed!` hand-rolled | One definition for `bb check-model-data` and runtime catalog loading; also the `~/.kmet/agent/models-cache` cache |
| `extension.edn` / bundled set | checks in `tasks/build.clj` and `app/extensions.cljc` | Same definition at build time and load time |

### Tier 2 — contracts the split exposes

| Surface | Why |
|---|---|
| `make-agent-state` bootstrap opts | The exact map duplicated between print and interactive; schema documents defaults and catches a mode that forgot a key |
| Event payloads | See §4.3 |
| RPC commands/responses | Validate at the transport edge; derive command docs from the registry |
| Extension ABI return shapes | Boundary returns `{:entries [...] :continue bool}`, input hooks `{:action :handled|:transform}`, tool-call hooks `{:block true :reason}` / `{:args ...}`, after-tool-call `{:content ... :is-error}`, `resources-discover` `{:skill-paths [...]}`. A schema lets the runner report a malformed handler result at the boundary |
| Tool definitions | See finding E |
| Session import | Reuses the session schema at the `:session-import/*` boundary |

### Tier 3 — do not schema

- `AgentState`, `CoreState`, TUI/GUI component records (atoms, protocols,
  derived caches).
- View-model shapes (footer, dock, selectors).
- Anything `map->Record` already enforces at construction.

### Validation policy

| Boundary | Policy |
|---|---|
| Session load | Validate (subject to finding C): header always, entries per the measured policy; failures report line + `malli.error/humanize` |
| Session append | Validate the single appended entry in tests; optional debug assert; never re-validate the file |
| Config load | Decode with defaults; throw only where the code throws today; unknown keys ignored |
| Events | Tests validate recorded scenarios; `--debug` may validate at `emit`; never in the hot path by default |
| RPC edge | Validate every command and response |
| Hot paths (`:message-update` per delta) | Never; if ever needed, memoized `m/validator` per type |

## 7. Staged plan

Each step is shippable alone and keeps the TUI working. Gates for every step:
`bb test-changed`, `bb test-ext-changed`, `bb lint-changed`,
`bb format-check-changed`; `bb test` / `bb lint` at step boundaries.

### Step 0 — contract harness (no behavior change)

- Add the EDN round-trip test and the event-vocabulary keyset-parity test
  (schema map ↔ `event-types`) — schemas may start as a stub registry.
- Add a headless conformance renderer: consumes the loop's events in a test
  scenario and renders plain text. Any view data not in the stream surfaces
  here.
- Drive scenarios through the existing test harness (`test/kmet/app/
  test_loop.clj` fake-provider pattern; `test/kmet/modes/test_print.clj`).
- Finding A decision + implementation (settle outcome) lands here or in
  step 1; it blocks print/RPC parity.

Acceptance: a scripted prompt run emits a stream that the conformance
renderer fully consumes; every event round-trips through EDN.

### Step 1 — engine facade and bootstrap

- Add the facade namespace (`kmet.engine` name is stage R; functions per
  §4.1) wrapping `run-agent-turn`, `make-agent-state`, `cancel-turn`, the
  queue helpers, and `app.session` mechanics.
- Add `bootstrap` with the single wiring map; print mode and interactive
  layout call it. Interactive keeps its own event handler as a subscriber.
- Subscriptions: `:on-event` becomes a subscriber list internally
  (extension bus untouched). Callbacks stay as sugar (finding B).
- Engine-scoped extension wiring: `set-session!`/`set-context-sink!` become
  facade lifecycle calls; `set-ui-registry!` gains a documented
  front-end-owned install point.

Acceptance: print mode and TUI both run through `bootstrap`; the duplicated
wiring blocks in `modes/print.clj` and `modes/interactive/layout.clj` are
gone; `test/kmet/app/test_interactive_ui.clj` and `test_print.clj` pass
unchanged.

### Step 2 — port print mode

- Print mode commutes its callbacks to subscriptions where practical;
  `:on-done` semantics come from the settle outcome (finding A).
- Delete `session-for-run` duplication in favor of the engine helper.

Acceptance: print mode has no direct `make-agent-state` call; all `test_print`
cases pass.

### Step 3 — move session/turn ownership under the engine

- Move `modes/interactive/session_admin.clj` switching/creation and
  `turn.clj` submission (input hooks, skill/prompt expansion, queueing) into
  the engine; modes keep selectors, rendering, and UX policy.
- Split slash commands into engine commands (return intents) and UI
  commands. `app.commands` handlers currently take `CoreState`; introduce an
  engine+effects context, migrate in place.
- `CoreState` shrinks to view state + engine reference.

Acceptance: `/new`, `/resume`, `/fork`, `/tree`, `/compact`, `/model`,
submit/steer/cancel work with the mode holding no engine atoms; the TUI test
suite passes.

### Step 4 — extension UI port + request/response

- Define the UI port capability map and the `:ui-request`/`respond!` pair.
- Interactive implements it as today (possibly via an in-process promise);
  print installs auto-answers; the global registry gains engine-scoped
  install/clear.

Acceptance: extensions using `ctx.ui` still pass `test/kmet/app/
test_extensions_ui.clj`; a print-mode extension dialog test answers with the
default.

### Step 5 — RPC mode

- `kmet.modes.rpc`: EDN lines in/out; commands (`prompt`, `steer`, `cancel`,
  `compact`, `set-model`, `new-session`, `respond`, `state`, `subscribe`),
  correlated responses, events forwarded as-is; extension UI requests
  forwarded.
- Reuse the conformance renderer as the RPC event encoder test.

Acceptance: an end-to-end test drives a prompt over pipes and observes the
event stream and a final response; no TUI namespace is loaded in the RPC
process (`require`-graph assertion).

### Step 6 — GUI mode (deferred)

- Subscribe and render through `kmet.gui.*`, marshaling to the toolkit
  thread. Shares only the engine, events, and data providers — not
  `app/ui/*`.

### Step R — renames (optional, late)

- `kmet.app.*` minus `ui` → `kmet.engine.*`; `kmet.app.ui.*` →
  `kmet.ui.tui.*` (or `kmet.modes.interactive.view.*`); `kmet.modes.rpc`
  as named. Mechanical, docs+tests, no behavior change. Do it after steps
  0–4 so the diff is renames only.

## 8. Enforcement

- **Layer guard** modeled on `test/kmet/libs/test_self_contained.clj`
  (which reads the `ns` form, not a regex): every `src/kmet/app/**` file —
  excluding `app/ui/**` until it moves — must not require `kmet.tui.*`,
  `kmet.gui.*`, or `kmet.modes.*`. Seed the allowlist with the four known
  violations (§3.6) and shrink it as they are fixed; a second guard runs
  after step R for the renamed tree.
- **Vocabulary parity**: `event-schemas`, `event-types`, `app-event-types`,
  `loop-event-types` keysets agree (test).
- **EDN contract**: recorded scenarios round-trip; the conformance renderer
  consumes every `loop-event-types` event with no internal-state access.
- **Docs**: update `src/kmet/README.md` (layer map, state conventions),
  `src/kmet/extension.md` (UI port, async UI requests), and
  `src/kmet/app/event_bus.clj` descriptions in the change that alters them.
  Add this file to the root-note list in `AGENTS.md` while it exists.

## 9. Dependency and portability notes

malli probes (scratch dirs under `target/`, removed by `bb clean`):

| Probe | bb v1.13.226 | jolt v0.8.21-179-g53218ea6 |
|---|---|---|
| `malli.core` require, validate, `:multi`, `m/explain` + `humanize` (0.19.1 and 0.20.1) | ✅ | ✅ |
| `malli.transform` string transformer | ✅ | ✅ only with the existing `io.github.jolt-lang/time` dep (without it: `No matching ctor found for class DateTimeFormatterBuilder`) |
| unknown `:type` rejected by `:multi` | ✅ | ✅ |

- malli is **not bundled** by either host (checked); add it to `deps.edn`
  and `bb.edn` and pin one version (0.20.1 was verified; re-verify with
  `bb check`, `bb lint` both reader views, and a `jolt -e` require probe).
- Prefer `malli.core` + `malli.error` only. `malli.transform` is optional;
  if used, it rides on the already-pinned `jolt-lang/time`.
- malli is not added to the fixed extension set (`kmet.app.extension-libs`);
  extensions do not get it unless that decision is made deliberately. If an
  extension-facing validator is wanted, `kmet.extension` can re-export a
  small `validate` helper later.

## 10. Open questions

1. Settle outcome shape (finding A): extend `:agent-settled` or add a
   distinct terminal event? Payload for queue drops on cancel (finding G)?
2. Session load validation policy (finding C) — measure first.
3. Multi-engine future: is per-engine extension scoping ever needed (RPC
   server with parallel sessions), or does single-engine-per-process stay
   permanent? This decides how hard to work on §4.5 scoping.
4. Command split granularity: intents/effects map vs direct engine calls
   with UI capabilities injected.
5. Does `:resume`'s selector remain a TUI-only policy hook, or does the
   engine expose a generic `:session-resolver` for RPC clients?
6. GUI timing: build after RPC (which proves headless conformance) or
   earlier against the TUI?

## 11. What not to do

- Do not rename namespaces before the facade and schema exist; the diff must
  stay reviewable.
- Do not build a network protocol or a plugin system first; EDN lines over
  stdin/stdout is enough to prove headless operation.
- Do not share TUI component trees with the GUI; share events and data
  providers.
- Do not close payload maps or validate the hot path.
- Do not schema internal mutable records or view models.
- Do not let `:on-text`/`:on-done` callbacks and the event stream grow as
  two independent APIs.
- Do not add extension-scoped exotic schemas (tool params, provider JSON
  schema derivation) before the core contract is stable — finding E.
