# kmet developer guide

This file documents the application package and its contributor workflow.
Implementation-specific references live beside their namespaces, not in the
user-facing `docs/` directory: see
[`tui.md`](../../../tui/src/kmet/tui/tui.md),
[`../../../loader/src/kmet/loader/loader.md`](../../../loader/src/kmet/loader/loader.md), [`extension.md`](extension.md),
[`agent/development/pi-alignment.md`](agent/development/pi-alignment.md). Temporary
analysis notes (`run_code.md`, `perf.md`, and `jolt-bugs.md`) remain at the
repository root by convention.

## Project structure

```
modules/
├── reakt/  — reactive primitives                    (kmet.libs.reakt.*)
├── hikap/  — toolkit-agnostic hiccup UI framework   (kmet.libs.hikap.*)
├── libs/   — generic, self-contained helpers        (kmet.libs.*)
├── tui/    — terminal backend over hikap            (kmet.tui.*; tui.md)
├── ai/     — provider/auth library                  (kmet.ai.*)
├── srce/   — the kmet2 kernel: ctx, services, fibers  (kmet.srce.*; srce.md)
├── app/    — kmet2's application, below every front end (kmet.app.*;
│             the file/namespace rule is new-app.md)   (kmet.agent.version)
├── agent/  — this module: kmet1, the current app    (kmet.agent.*)
├── loader/ — extension loader                       (kmet.loader.*)
└── tk/     — Tk backend + widget sample, Jolt-only  (kmet.tk.*, kmet.gui-sample.*)
```

`modules.edn` is the workspace manifest: it lists the module trees and the
extra source roots, and the tasks read it instead of repeating the paths
(`kmet.tasks.modules`). `kmet.libs.hikap.*` is namespaced under `kmet.libs`
(the framework may require only `kmet.libs.*`) but its sources live in
`modules/hikap`.

Ranks are the manifest's layering guard (`kmet.test-module-deps`): a module
may depend only on strictly lower-ranked ones, and a *namespace* may require
only a file in a strictly lower-ranked module (`srce` 1, `app` 2, `agent` 4 —
the legacy app sits above the shared layers until it is retired). A file may
change module without changing namespace: `kmet.agent.version` lives in
`modules/app` so both apps can print one version string, and the module's
`deps.edn` carries the edge (`agent` → `app`). `kmet.agent.core` in the agent
module is still the only process entry — it is a bootstrap that selects the
app (see below).

The extension contract sits above the app tree, beside it:
`modules/app/src/kmet/extension.clj` (`kmet.extension`) is the one kmet
namespace an extension may depend on; `kmet/agent/extensions/` is its
runtime.

The app module (`modules/agent/src/kmet/agent`):

```
kmet/agent/
├── core.clj            — the process bootstrap: --app old|new dispatch, no
│                         app require (kmet.agent.cli / kmet.app.core load
│                         through requiring-resolve)
├── cli.clj             — kmet1's CLI: global flags (--version, --list-models,
│                         --generate-models), argument parsing, mode dispatch
├── jvm_main.clj        — the JVM artifact's AOT entry point (delegates to
│                         core.clj, i.e. the bootstrap)
├── config.clj          — configuration loading (settings.edn, env vars)
│   config_schema.clj   — the malli schema for the config map
├── debug.clj           — debug/error logging
├── project_context.clj — project context files (AGENTS.md/CLAUDE.md)
├── app_runtime.clj     — the app root store node + the loop's stage map
├── registry.clj        — name-addressed registrations in the app root's
│                         store (commands, prompts, skills, tools)
├── extensions/         — the extension runtime
│   ├── core.cljc       — hook/renderer/shortcut/flag registries, bind and
│   │                     install-front-end!, the extension UI ports
│   ├── context.cljc    — per-extension SCI isolation
│   ├── libs.clj        — the fixed bundled-extension library set
│   └── bundled.clj     — bundled extension artifacts and discovery
├── commands.clj        — slash-command registry (app-root store,
│   │                     [:app :commands] owner; ctx-shaped handlers:
│   │                     {:runtime :ui :frontend}, front-end tags)
│   └── commands/builtins.clj — engine-portable builtin commands
├── keybindings.clj     — the app keymap
├── prompts.clj         — prompt-template resources
├── skills.clj          — skill resources
├── model_resolver.clj  — model pattern/CLI resolution
├── packages.clj        — package/resource resolution (local + managed git)
├── packages_cli.clj    — the install/remove/update/list/config CLI
├── git_packages.clj    — git source parsing + managed checkouts
├── event_bus.clj       — kmet1's extension event bus (the vocabulary is
│                         re-exported from kmet.app.events)
├── loop.clj            — the agent loop driver (public: make-agent-state,
│   │                     run-agent-turn, cancel-turn)
│   └── loop/           — loop submodules, a DAG pinned by test-layer-guard:
│       ├── message.clj     message constructors + wire shapes
│       ├── state.clj       agent state, event routing, queue writes
│       ├── completion.clj  LLM request construction + provider call
│       └── tool_exec.clj   tool-call prep, hooks, batch execution
├── compaction/         — the compaction subsystem
│   ├── core.clj        — what compaction is: token estimation, cut point,
│   │                     serialization, summarization/branch prompts
│   └── stage.clj       — when the loop runs it: proactive/reactive triggers,
│                         summarization call + retry, session replacement
├── retry.clj           — retry decision stage
├── loop_guard.clj      — repeat-loop guard stage
├── session/            — session subsystem
│   ├── core.clj        — the session API façade over store/listing/
│   │                     context/stats/tree (what dependents require)
│   ├── store.clj       — EDNL storage: Session record, ids, line parser
│   │                     and torn-tail repair, atomic rewrite, appends
│   ├── listing.clj     — on-disk discovery: header scan, session list,
│   │                     delete and import
│   ├── context.clj     — message context, append-only context edits,
│   │                     cache-miss detection
│   ├── stats.clj       — usage/tokens, counts, prompt history, activity
│   ├── tree.clj        — branches, labels, tree projection, fork/clone
│   ├── schema.clj      — malli schemas for session entries/header
│   ├── export.clj      — standalone HTML session export
│   └── runtime.clj     — the session runtime engine (bootstrap, lifecycle,
│                         submission chain, compaction queue, subscribers,
│                         UI requests, disposal)
├── tools/              — built-in tools: read, write, edit, bash
│   │                     (grep/find/ls and the Windows-only powershell ship
│   │                     as opt-in extensions)
│   ├── core.clj / tool.clj / registry.clj — the tool API, Tool record and
│   │                     registry (custom tools live in the app-root store)
│   ├── invoke.clj      — the shared invocation pipeline (loop + run_code)
│   ├── util.clj        — path/argument helpers
│   ├── bash_executor.clj — the bash tool's process engine (streaming,
│   │                     temp-file spill, shell resolution)
│   ├── bash_output.clj — the engine's pure half: size formatting, UTF-8
│   │                     truncation and output sanitizing (read, run_code
│   │                     and the renderers use it without the engine)
│   └── config.clj      — the built-in tool rows for `kmet config`
├── modes/              — entry modes
│   ├── interactive.clj — interactive TUI entry: run + session start-up
│   │                     (pi: modes/interactive/interactive-mode.ts)
│   ├── interactive/    — the TUI application proper, split out of
│   │                     interactive.clj: layout (agent event handler +
│   │                     the widget tree), turn (submit/cancel/dequeue),
│   │                     session admin (replay/resume/tree/fork), builtins,
│   │                     state (CoreState), status, auth, resources,
│   │                     ui registry (ExtensionUIContext)
│   ├── print.clj       — non-interactive print mode (`--print`;
│   │                     `--mode text|edn` output)
│   ├── rpc.clj         — the long-lived EDN stdio protocol (docs/rpc.md)
│   ├── rpc_client.clj  — the thin client scripts and tests drive RPC with
│   └── config_command.clj — the `kmet config` terminal screen
└── ui/                 — app UI over the front-end backends
    ├── shared.clj      — backend-neutral view helpers (no kmet.tui/kmet.tk)
    └── tui/            — TUI components: chat history, tool renderers,
                          selectors, dock, footer, dialogs, the theme
                          controller, ...
                          (the Tk widget sample lives outside this module: it
                          is kmet.gui-sample.* in modules/tk, so the agent has
                          no Tk dependency)
```

```
tasks/kmet/tasks/       — every bb-task implementation (a classpath root, not
                          part of the app): packagers (uberjar/dist/pack-extension),
                          the catalog generators, and the dev loop (changed,
                          test runner, clean, lint). Neither artifact carries
                          it — the uberjar walks src/, jolt embeds src/.
test/kmet/tasks/        — their tests
docs/examples/          — copyable, non-auto-loaded settings and complete theme EDN
```

The tree is a working map: update it in the same change that moves a
namespace, and keep detailed behavior in the topic documents listed in the
root `AGENTS.md` topic map.

## Architecture and portability

### Layer boundaries

- `kmet.libs.scope` is the store and node lifetimes (in `modules/libs`, rank
  0): registrations keyed by owner path, with LIFO, isolated disposal; a node
  links its parent and children, and disposing one ends its children first. It
  is stricter than the rest of libs: no `kmet.*` require at all and no
  third-party library; `kmet.libs.test-scope-self-contained` enforces the
  require rule. Design: `modules/libs/src/kmet/libs/scope.md`.
- `kmet.loader.*` is an outer module (`modules/loader`); it may use
  third-party deps and `kmet.loader.*`, but no other `kmet.*` namespace.
- `kmet.libs.*` is generic and self-contained; it may not require non-libs
  `kmet.*` namespaces (app, TUI, mode, AI). Sibling-lib composition is fine
  and expected (`http → concurrent/json/process`,
  `oauth → concurrent/crypto/http/json`, `reakt → weak`);
  `modules/libs/test/kmet/libs/test_self_contained.clj` enforces exactly this.
  `kmet.libs.reakt.weak` is the one namespace allowed to touch `java.lang.ref` —
  a platform exception like the terminal backends.
- `kmet.libs.hikap.core` is the toolkit-agnostic UI framework owned by
  `modules/hikap` (namespaced under `kmet.libs` because it may require only
  `kmet.libs.*`): the hiccup engine (`kmet.libs.hikap.core`), the component
  runtime (`kmet.libs.hikap.component`) and the alias registry
  (`kmet.libs.hikap.alias`). `kmet.tui.*` and `kmet.tk.*` are backends over
  it — they pass their backend map in, the engine never requires them.
- `kmet.ai.*` is a standalone provider/auth library and depends only on
  `kmet.libs.*` plus standard/third-party code.
- `kmet.tui.*` is the generic UI layer (the terminal backend for
  `kmet.libs.hikap.core`). It may depend on `kmet.libs.*`, never on app, LLM, or
  session concepts.
- `kmet.tk.*` is the Tk GUI backend (Jolt-only; kmet.tk.core with the
  classic `:tk/…` widget set, kmet.tk.ttk the `:ttk/…` set, kmet.tk.dsl
  their shared machinery and kmet.tk.options the Tk option vocabulary —
  plus kmet.tk.tcl, the host FFI adapter). Like `kmet.tui.*`, `kmet.tk.*`
  builds on `kmet.libs.*` and nothing above it. The module also carries the
  `kmet.gui-sample.*` widget skeleton (the `:ui/…` widgets, today
  `:ui/transcript`), which is dependency-free like the backend; no app module
  requires the sample or Tk.
- `kmet.agent.modes.*` is the entry-mode layer; `kmet.agent.*` is business
  logic; `kmet.agent.ui.*` is app-specific UI. The direction is modes → UI (a
  mode assembles components), never UI → modes;
  `kmet.agent.ui.tui.theme-controller` sits in the UI layer for exactly that
  reason and the modes call it from above.
  `kmet.agent.cli` is the composition root: it owns the CLI-only flags
  (`--version`, `--list-models`, `--generate-models`), parses arguments and
  dispatches to a mode. `kmet.agent.core` above it only selects an app:
  `--app old` (the default) hands the argv to `kmet.agent.cli`, `--app new` to
  `kmet.app.core` (new-app.md § One artifact, two apps); the flag is stripped
  and neither app is a static require.
- `kmet.tasks.*` lives under `tasks/kmet/tasks/`, is never required by shipped
  code, and is not packaged. Task tests live under `test/kmet/tasks/`.
- Facilities are extended at the layer that owns them, never copied into the
  caller: upper layers call the `kmet.tui.*` / `kmet.libs.*` API. The
  `kmet.tui.core` dispatchers (`editor-get-text`, `editor-set-text!`,
  `editor-add-to-history!`, …) exist so app and mode code never open-codes the
  `satisfies?`/field-based fallback pair; a variant belongs in the lower
  layer.

Prefer `defrecord` over `deftype`, protocols for extension points, and atoms
for mutable state. `AgentState` deliberately uses independent per-field atoms
rather than one application-database atom. Prefer explicit runtime
registration tables/maps over compile-time multimethod registries. Use
`defn-` and `def-` for implementation details that are not public API.

The registries a front end or extension can reach — commands, tools, skills,
prompt templates, hooks, renderers, markdown transformers, flags — live in the
app root's store (`kmet.libs.scope`, reached through `kmet.agent.app-runtime`),
addressed by host-shaped keys under an owner path: a registry's address
(`[:app :commands]`), or `[:app :extensions <name>]` for an extension's own
registrations, including the skills, prompts and themes a
`:resources-discover` contribution loads (P5c). The registration is disposed
with that owner (P2), so a new extension point is a key on that store, not a
process-global atom copied into each engine. `/units` prints every store
owner — a registry's address, an extension's own registrations — one flat
line each, from the store itself (F5, F14, scope.md §6). The runtime-owned
surfaces follow the same rule: `bootstrap` registers the live session and its
sinks under `[:app :session]` and the headless UI port under `[:app :ui]`, and
`dispose-owner!` drops those owners; the `extensions` atoms stay the no-node
read cache for extension code with no node in hand (P4).

### Session runtime and front ends

The session-runtime split puts one session runtime behind every
front end:

- `kmet.agent.session.runtime` owns the agent state, the session lifecycle
  (`new-session!`, `resume!`, `fork!`, `clone!`, `switch-session!`), the
  submission chain, the steer/follow-up queues, compaction (a manual request
  arriving mid-turn is deferred until the turn settles —
  `request-compaction!`), and the extension runtime handle. Front ends call
  `bootstrap` once and drive the engine through its operations: the TUI keeps
  its `CoreState` view state plus the engine, and print/RPC modes construct
  no agent state at all.
  Modes and app UI (kmet.agent.modes.**, kmet.agent.ui.**) use the runtime's
  front-end surface only — they never require `kmet.agent.loop` — and
  `kmet.agent.test-layer-guard` enforces that boundary.
- The event stream is the API. `subscribe!` delivers plain EDN events on the
  emitting thread (fast, non-blocking) and the vocabulary — `event-types` /
  `app-event-types` / `loop-event-types`, declared as `:events` in
  `kmet.app.events/plugin` and read from that declaration,
  re-exported by `kmet.agent.event-bus`, which keeps kmet1's own table and
  emitters until the loop re-points (`new-app.md`, landing 2) — is the shared
  contract; `:agent-settled` carries the
  run's `:outcome` (`:completed` / `:error` / `:aborted`). A front end that
  needs data not on the stream extends the event, not the reader.
- Extension UI splits into runtime capabilities (installed by `bootstrap`,
  cleared by `dispose!`) and the front-end UI port
  (`extensions/install-front-end!`, registered under `[:app :ui]`). Dialogs
  ask through
  `request-ui!`/`respond!`, so an asynchronous client can answer later; the
  degraded headless answers and the per-front-end capability table are in
  [`extension.md`](extension.md).
- The RPC front end ([`docs/rpc.md`](../../../../docs/rpc.md)) is the headless
  consumer and `kmet.agent.modes.rpc-client` is the scripted client;
  `modules/agent/test/kmet/agent/modes/test_headless_guard.clj` asserts print
  and RPC reach no interactive, TUI, or Tk namespace at all.

Tier 1 malli boundary schemas for session entries, config, and auth landed.
The GUI live milestone remains the final GUI-dependent work.

### Session storage façade

`kmet.agent.session.core` re-exports the storage API (`store`, `listing`,
`context`, `stats`, `tree`) as `(def name impl/name)` aliases; dependents
require the façade, and `modules/agent/test/kmet/agent/session/test_facade.clj`
pins its exact public surface. Two consequences for tests and REPL work:

- The aliases capture values at load time — after redefining an
  implementation var, reload `core` (or the whole tree) too.
- A `with-redefs` on a façade alias cannot intercept a call made inside an
  implementation namespace. `context`, `stats` and `tree` call
  `store/get-branch`, `store/get-entry` and `store/append-entry` directly
  (`context/derive-context-settings`, `context/append-context-edit!`,
  `context/build-context`, `stats/get-prompt-history`,
  `stats/get-last-assistant-text`, `tree/common-ancestor-id`,
  `tree/fork-session`), so stubbing `session/get-branch` only affects callers
  that go through the façade — redef the implementation var when the code
  under test runs inside those namespaces.

### Portable host rules

- Prefer `babashka.fs`, `babashka.process`, `clojure.string`, and
  `clojure.java.io`; avoid `java.io.*`/`java.nio.file.*` imports and Java type
  hints. All outbound HTTP goes through `kmet.libs.http`.
- Durations and deadlines use `kmet.libs.concurrent/monotonic-ms` (timeouts,
  sleep slices, backoff, idle reaping, elapsed counters) — never
  `System/currentTimeMillis`, which steps. The wall clock is only for absolute
  values and for comparisons with externally-produced wall-clock data
  (token/JWT expiry, epoch ids, file mtimes, persisted cross-process TTLs).
- Host reader features are `:bb` and `:jolt`, never `:clj`. For a portable
  host carve-out use `#?(:jolt X :default Y)`.
- `kmet.agent.cli/-main` ends with `shutdown-agents` + `System/exit 0`: Jolt waits
  for non-daemon threads before exiting, as the JVM does, so the agent pool
  behind `future` would otherwise hold the process open after `/quit`; the
  explicit exit also covers any thread stuck outside that pool. bb exits on
  its own and both calls are harmless there.
- Do not add Maven entries for `babashka.fs` or `babashka.process`; both hosts
  provide their built-in equivalents. Tooling-only dependencies belong in
  `bb.edn`/`deps.edn`.
- On Termux use `$TMPDIR` or another explicit writable directory rather than
  `/tmp`; `java.io.tmpdir` is unreliable there. Normalize zip entry separators
  before containment checks and normalize `fs/relativize` results with
  `fs/normalize` before comparing or using them as path segments.
- The shell setting resolves a custom path first, then `/bin/bash`, `which
  bash`, and `sh`; Windows uses Git Bash and WSL uses the WSL shell.

## Development workflow

The task implementation lives in `tasks/kmet/tasks/`; packaging and
cross-host build details are covered in the user-facing
[`docs/building.md`](../../../../docs/building.md) guide.

```sh
bb start           # Interactive TUI
bb test            # Run fast test suites (excludes ^:slow tests)
bb test-ext        # Run only the slow (^:slow) test suites
bb lint            # clj-kondo over both reader views (babashka + jolt);
                   # `jolt lint` runs the same gate
bb format          # cljfmt (fix) / bb format-check (verify)
bb clean           # Remove build artifacts, caches, logs (--dry-run: list only)
bb nrepl           # Start an nREPL server on port 7801 (blocks)
bb nrepl-jvm       # Start a JVM nREPL server on port 7803 (blocks)
bb check           # Verify all source namespaces compile
bb generate-models     # Regenerate provider + image model catalogs (network)
kmet --generate-models # Refresh the user-level catalog caches (network)
bb check-model-data      # Offline catalog validation (providers + images)
bb slop                # SCBench verbosity (clones+rules)/erosion vs. reference rows, outliers only; --dups lists duplicate groups (source trees; --test adds tests)
bb pack-extension <src-dir> [out.jar]  # Verify + pack an extension artifact root
bb help            # Show task entry points
```

`bb tasks` prints one-line summaries; `bb <task> --help` (the same task works
under `jolt`) prints a task's full usage, arguments and notes.

The `bb nrepl` task starts a Babashka nREPL on port 7801 and writes no port
file; `bb nrepl-jvm [port]` starts the JVM equivalent (default 7803, under the
app's JVM aliases and with the test paths) and writes `.nrepl-port`
(gitignored), which `clojure_eval` discovers first. Jolt's built-in
`jolt nrepl-server 7802` is the Jolt server (its own default is 7888, so pass
7802 or set `JOLT_NREPL_PORT=7802`) and writes the same `.nrepl-port` file;
when the JVM and Jolt servers both run, pass an explicit `port` to
`clojure_eval`.

The `*-changed` tasks are the iteration loop — they cover only the current
changes (git diff vs HEAD + untracked, plus the namespaces/tests that
transitively require them via the require graph); `jolt` runs the same tasks:

```sh
bb changed              # List changed files
bb test-changed         # Tests of affected namespaces (non-slow)
bb test-ext-changed     # ...only the slow ones
bb lint-changed         # Lint changed files plus affected dependents
bb format-check-changed # (or bb format-changed to write the fix)
```

The full gates — `bb test`, `bb test-ext`, `bb lint` (0 findings required) and
`bb format-check` — are slow. Use the changed-file tasks while iterating and
run the full gates when requested or before release.

The commit-msg hook installed by `sh scripts/install_hooks.sh` refuses
co-author trailers, AI session links, machine addresses and generated-with
footers at commit time; the attribution workflow
(`.github/workflows/attribution.yml`) checks the same rules over a pushed or
PR range. It is the repository's only CI check.
