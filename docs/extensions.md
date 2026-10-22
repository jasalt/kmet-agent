# Writing extensions

An extension customises kmet with slash commands, tools, event handlers,
hooks, CLI flags, custom message/entry renderers, UI contributions, and more.
It is an ordinary Clojure namespace with an explicit contract: it depends on
exactly one namespace, `kmet.extension`, and is loaded, reloaded and unloaded
while kmet runs.

This guide covers authoring, packaging and testing an extension. The
authoritative reference for what each `kmet.extension` function takes and
returns, the `api`/context map keys, the allowed namespaces and the lifecycle
guarantees is
[`modules/app/src/kmet/extension.md`](../modules/app/src/kmet/extension.md);
keep that contract reference and this guide in sync when behavior changes.

Related: [customization](customization.md) (user-facing setup and discovery),
[`extensions/README.md`](../extensions/README.md) (the shipped catalog and the
repo layout it uses), and [rpc.md](rpc.md) (extension UI over RPC).

## Hello, extension

```clojure
(ns my.hello-ext
  (:require [kmet.extension :as ext]))

(defn init [api]
  (ext/register-command! api
    {:name        "hello"
     :description "Say hello"
     :handler     (fn [ctx args] (str "Hello, " args "!"))}))

(defn shutdown [api]
  ;; optional teardown — deregistration is automatic
  nil)
```

The loader calls `(init api)` with the capability map; everything registered
inside `init` belongs to the extension, and unload disposes it automatically
— `shutdown` is optional. The full contract (ownership, name conflicts,
lifecycle guarantees) is in the
[contract reference](../modules/app/src/kmet/extension.md#the-contract).

## Where extensions live

Extensions load from the fixed auto roots — `~/.kmet/agent/extensions`
plus `.kmet/extensions` project-local (pi: `join(agentDir, type)` +
`join(cwd, CONFIG_DIR_NAME, type)`) — at startup and on `/reload`. The
agent dir honors the `KMET_CODING_AGENT_DIR` env override (pi:
`ENV_AGENT_DIR`). Extra paths load via top-level `:extensions` entries in
`settings.edn`: plain entries are files or directories resolved against
the settings scope root (the agent dir for global settings, `.kmet` for
project settings); `!glob` excludes, `+path`/`-path` force include/exclude
exact paths. Plain entries may also carry the same auto-root paths to
select which discovered items stay enabled (e.g.
`{:extensions ["extensions" "!extensions/noisy.clj"]}`). The
`kmet config` screen lists and toggles every discovered resource
(extensions, skills, prompts, themes) across both scopes.

## Built-in extensions (`extensions/`)

The repo ships a set of opt-in extensions in `extensions/` — nothing there is
loaded by default. They are **bundled with the application**: every run mode
(`bb start`, `jolt start`, and the built binaries) offers the whole set in
`kmet config` under “Bundled with kmet”, and the same set is enableable from
the settings files via the `:bundled-extensions` key (resource-array
vocabulary: `+`/plain enables, `-`/`!` disables; a project entry is a delta
over the global one; default disabled):

```edn
;; ~/.kmet/agent/settings.edn
{:bundled-extensions ["clojure" "-mcp-adapter"]}

;; .kmet/settings.edn (project delta)
{:bundled-extensions ["+mcp-adapter"]}
```

Bundled extensions are not packages: `kmet install`/`remove`/`list` cannot
see or affect them, and they are never extracted to disk. The artifact root
is the same path in every mode — `extensions/<name>/src` for directory
artifacts, `extensions/<file>.clj` for single files; in a checkout they load
from the real files (directory artifacts natively on Jolt), and in a built
artifact through the bundled resource tree. On Jolt with embedded loader
roots, directory artifacts use the native loader over `embed:<prefix>`, and
single-file resources map their namespace directly to the exact embedded key
(for example `kmet.extensions.deepseek-peak` to
`extensions/deepseek-peak.clj`). Older Jolt releases fall back to SCI.

The shipped set is enumerated in `modules/agent/src/kmet/bundled-extensions.edn`;
`bb check-bundled-extensions` validates it (every artifact under
`extensions/` is listed or excluded, directory manifests declare `:sci`,
strict ns-path layout, `deps.edn` with only `:deps` and no `:local/root`).

To develop against an edited copy, the classic paths still work — symlink
(or copy) an extension into the global or project extensions dir:

```bash
ln -s "$PWD/extensions/tools.clj" ~/.kmet/agent/extensions/tools.clj
```

Built-in extension namespaces follow the layout: a single file is
`kmet.extensions.<name>` (the file's stem); a manifest dir namespaces its
code `kmet.extensions.<name>.*` with the manifest `:entry` at
`kmet.extensions.<name>.core`.

Single-file extensions ship without tests (they must stay small and
self-contained); directory-based extensions are separate projects with
their own tests. See [`extensions/README.md`](../extensions/README.md) for the
shipped layout (`src/` is the artifact root) and the catalog.

## Single-file extensions

A `.clj` file in the extensions directory:

```
~/.kmet/agent/extensions/
└── hello_ext.clj        ; namespace must start with (ns ...) and define init
```

## Directory extensions (multi-file, `extension.edn`)

A directory containing an `extension.edn` manifest supports extensions with
their own source files. The directory root is a classpath root: namespace
`my-ext.main` lives at `my_ext/main.clj` (strict ns-path layout — dashes →
underscores, dots → slashes; `.clj` → `.cljc` → `.bb` fallback):

```
~/.kmet/agent/extensions/
└── my-ext/
    ├── extension.edn
    ├── deps.edn          # optional: external library dependencies
    ├── my_ext/
    │   ├── main.clj
    │   └── helper.clj
    └── skills/           # optional bundled resources (exact-name access)
        └── my-skill/SKILL.md
```

```clojure
;; extension.edn
{:name   "my-ext"
 :entry  my-ext.main
 :loader [:jolt :sci]}
```

```clojure
;; deps.edn (optional)
{:deps {cheshire/cheshire {:mvn/version "11.5.3"}}} ;; or just use kmet.libs.json
```

The manifest lists the initial namespace — `:entry`, a namespace symbol
whose namespace must define `init` — and the loader backends the extension
supports — `:loader` (see
[Loader compatibility](#loader-compatibility-loader)). It may also declare
`:inject`, the app services `init` waits for:

```clojure
{:name "my-ext" :entry my-ext.main :loader [:jolt :sci]
 :inject [:tools :commands]}      ; or {:tools nil :ui :optional}
```

With `:inject`, the extension mounts as a plugin: it stays **pending** while
a declared service is absent, `init` runs the moment the last one appears
(and again if kmet reloads the plugin), and unloading disposes what it
registered. Without `:inject` it loads immediately, exactly as before —
nothing in an existing extension needs to change. A name is a service
keyword the app provides (`:tools`, `:commands`, `:prompts`, `:skills`,
`:sessions`, `:ui`, an app-defined one); `:optional` entries do not hold the
mount. Everything else is
required from there:
internal namespaces resolve by strict ns-path lookup under the extension
root, and declared library dependencies resolve to the jars in `deps.edn`
(see [External library dependencies](#external-library-dependencies-depsedn)).
Internal dependencies are declared in each file's `ns` form:

```clojure
;; my_ext/helper.clj
(ns my-ext.helper)

(defn tool-name [] "my-ext-tool")
```

```clojure
;; my_ext/main.clj
(ns my-ext.main
  (:require [my-ext.helper :as helper]   ; internal dependency
            [kmet.extension :as ext]))   ; kmet core, resolved normally

(defn init [api]
  (ext/register-tool! api {:name (helper/tool-name)
                           :description "..."}))
```

Requires that don't resolve to a file under the extension root (kmet core
namespaces, built-ins, declared libraries) are left to the normal classpath.

An extension's **own** namespaces always resolve internally — regardless of
their prefix. A manifest-dir extension named `tree-sitter` may therefore
have its entry require `kmet.extensions.tree-sitter.tools`: the loader
probes the ns path under the extension root and resolves those requires
before the shared-namespace allowlist is consulted.
The extension name defaults to the directory name (`:name` in the manifest
overrides it).

## Jar/zip extensions (no expansion)

A `.jar` (or `.zip` — same bytes, either suffix) with the directory layout
above at its root loads exactly like the directory: `extension.edn` (+
optional `deps.edn`) on top, code at ns paths, resources by exact name.
The archive is **never expanded on either host** — code and manifests are
served from the zip (babashka probes entries per call through `ZipFile`; on
Jolt the archive is the loader's source root, read through its central
directory), and resources resolve through the archive (babashka:
an artifact-scoped `io/resource`; Jolt: the loader's own resource
resolution, a `jar:file:…!/entry` URL). Nothing is written outside
`~/.m2`/`~/.gitlibs`.
Discovery picks up top-level `*.jar`/`*.zip` files
alongside `*.clj` files and manifest dirs. Pack one with
`bb pack-extension <src-dir> [out.jar]` (verify-then-zip: `:entry`
resolves, every `.clj` ns matches its path, `deps.edn` carries only
`:deps`).

## Loader compatibility (`:loader`)

The manifest declares which loader backends the extension supports:

```clojure
{:name   "my-ext"
 :entry  my-ext.main
 :loader [:jolt :sci]}      ; both backends — see the table below
```

- `:jolt` — Jolt's native loader (real Jolt namespaces; Jolt's default).
- `:sci` — the SCI backend (babashka's and the JVM's only backend; Jolt can
  run it as a fallback).

The host picks from the declared set by its own preference — **the order in
the manifest is not a ranking**:

| host | prefers | selected |
|---|---|---|
| babashka / JVM | `:sci` | `:sci`, when declared |
| Jolt | `:jolt`, then `:sci` | `:jolt` when declared, else `:sci` |

An extension declaring none of the host's backends is **skipped**, not
failed: it is discovered but not loaded, with a note (and a line in the
`/reload` summary). One artifact directory therefore stays portable across
hosts — declare what you support and each host uses its best backend.

A manifest without `:loader` (a legacy extension) is treated as
`[:sci :jolt]` with a warning — exactly the backend it used before the key
existed. A single-file `.clj` extension has no manifest and implicitly
supports both. `bb pack-extension` requires `:loader` for new artifacts.

What the two backends mean in practice is in
[Host support (Jolt)](#host-support-jolt) below.

### Host support (Jolt)

Jolt prefers its own loader: an extension's context there is a set of real
Jolt namespaces, read by the native reader from the extension's own sources
plus its declared deps, shared host layers coming back by reference, and
teardown unmapping what it loaded (`kmet.loader.jolt-loader` adapts the
runtime's loader to the same protocol the bb/JVM SCI backend implements).
An extension declaring `:sci` without `:jolt` instead runs through the SCI
backend on Jolt — the same one babashka uses — as a fallback for extensions
written against the SCI environment. Host differences are implementation,
not author-visible:

- **Classes.** Classes resolve through the compiler, against the runtime's
  class graph — there is no lazily-registered class map (the SCI backend
  builds one: `{:classes {:allow :all}}` delegates instance calls,
  `bb-imports` covers statics/ctors/hints). A class Jolt's graph does not
  supply (e.g. some JDK classes reachable only through a type hint) fails
  the load.
- **SCI.** Jolt's *fallback* backend: an extension whose manifest declares
  `:sci` without `:jolt` evaluates through the same SCI context as on
  babashka. `jolt/deps.edn` pins the SCI build that fixes
  `defrecord`/`extend-type` over host protocols copied with `sci/copy-var*`
  (jolt#1031 / babashka/sci#1093), reader features follow the host (`:jolt`
  on Jolt, so `#?(:bb … :jolt …)` selects what the host selects natively),
  and SCI's `*out*`/`*err*` are bound around evaluation and registered
  callbacks.
- **Deps.** bb resolves the closure to jars and serves them with `ZipFile`;
  Jolt uses `jolt.deps/resolve-deps`, which returns the dependency jars
  unexpanded (Maven/git) or `:local/root` paths — both are read in place
  (`extension-jars` returns jars on both hosts).
- **Jars.** Neither host expands an extension archive: babashka probes
  entries per call with `ZipFile`; Jolt passes the archive to the native
  loader as a source root, so a `require` reads the central directory and
  resources answer `jar:file:…!/entry` URLs.
- **Bundled libraries.** The fixed bundled set (see the contract reference's
  § Bundled extension libraries) is shared on both hosts: bb's ports serve
  the namespaces there, Jolt compiles kmet's pinned Maven deps into the
  binary and the host view shares them. `clojure.data.xml` is not in the set
  and is not shared on either host: an extension needing it declares a Maven
  dep, served by Jolt's native loader (its Maven copy uses `definline`,
  which SCI rejects).

## External library dependencies (`deps.edn`)

An extension directory may declare its own library dependencies in a
`deps.edn`; the extension's `ns` form can then require them normally
(`[clojure.data.json :as json]` or simply `[kmet.libs.json :as json]`). kmet
resolves the declared dependencies — the complete transitive closure — and
serves them **only to that extension's evaluation context**. Every extension
runs in its own isolated context (the guarantees are in the contract
reference's § Allowed namespaces and isolation):

- kmet core and `clojure.*` / `babashka.*` namespaces are shared references
  (never re-evaluated), so extension registrations always reach the real
  kmet registries. Extensions may depend only on `kmet.extension` plus the
  shared `kmet.tui.*` / `kmet.libs.*` libraries; other kmet internals are
  not resolvable from an extension context (the load fails with an explicit
  error) — the loader, `kmet.loader*`, is one of those internals.
- **Contexts are forks of one shared base** — the shared layers and the
  seeded classes are built once and forked per extension, so your
  definitions live only in your context (two extensions may even declare
  the same internal namespace name).
- **A `jolt dist` binary cannot resolve deps today** — the app image doesn't
  carry `jolt.deps`, so an extension with a `deps.edn` warns and skips there
  (dev jolt and every bb mode resolve normally). The shipped set declares no
  `deps.edn`.

Dep resolution happens **in-process** (via `clojure.tools.deps`, bundled
with babashka — no extra dependency, no JVM) — no subprocess, and nothing
is written outside the normal Maven/Git caches (`~/.m2`, `~/.gitlibs`). A
library an extension requires without declaring it in `deps.edn` fails with
a clear error unless it is part of the fixed bundled set below.

### The fixed bundled set

kmet ships a fixed set of libraries in **both** artifacts and shares them by
reference with every extension context and `run_code` script, so requiring a
set member needs **no `deps.edn`** and no host-conditional code (the table
and the portability guarantee are in the contract reference's
§ Bundled extension libraries).

The **guarantee is the namespace surface, not the version**: on babashka a
bundled port decides its own edition, while Jolt uses the version kmet pins
in `deps.edn`. Pinning a set member in an extension's `deps.edn` therefore
does **not** override the shared copy — declare only libraries **outside**
the set.

Everything else babashka bundles (cheshire, clj-yaml, http-kit, selmer,
transit, hiccup, timbre, nextjournal.markdown, bencode, …) is **not** part of
the portable surface: those are neither injected nor source-evaluable under
SCI — Java-backed copies fail on classes the native image does not expose —
and Jolt has no copies at all. Use `kmet.libs.json`, `kmet.libs.yaml`,
`kmet.libs.markdown` and `kmet.libs.http` instead.

### Limits (inherent to Babashka)

- Babashka only runs libraries it supports: pure-Clojure code using classes
  it exposes. Libraries needing `definterface`, `deftype` with non-protocol
  interfaces, or unexposed Java classes fail to load — in plain bb too.
- The fixed bundled set (see the contract reference's § Bundled extension
  libraries) is injected by reference: omit those libs from `deps.edn` (a
  declared copy is ignored).
- Libraries babashka implements natively (cheshire, clj-yaml, http-kit,
  selmer, ...) usually cannot be replaced by their raw Maven versions — the
  Java classes are missing or not registered for reflection — and are not
  in the set; kmet warns when an extension pins one. Use the `kmet.libs.*`
  seams instead. The `clojure.data.xml` family is not provided either: an
  extension needing it declares a Maven dep, served by Jolt's native
  loader (its Maven copy uses `definline`, which SCI rejects).
- Single-file extensions (plain `.clj` files, no directory) cannot carry a
  `deps.edn` — they can use the fixed set and the shared libraries, and
  nothing else.

## Outbound HTTP (`kmet.libs.http`)

All outbound HTTP from an extension goes through `kmet.libs.http` — the
single proxy-aware boundary shared by reference with the host. Requiring
`babashka.http-client` directly fails the load with an actionable error.

```clojure
(ns my-ext.main
  (:require [kmet.libs.http :as http]))

(http/get "https://api.example.com/status" {:timeout-ms 5000})
;; => {:status 200 :headers {...} :body "..."}
```

- `request` / `get` / `post` / `request-json` with `:as` (:string/:bytes/
  :stream), `:timeout-ms`, `:throw?` (default true — HTTP >= 400 throws
  `{:type :http-error :status :headers :body}`), `:follow-redirects`,
  `:signal` (cancel atom, curl transport) and `:proxy` (:env by default —
  HTTPS_PROXY/HTTP_PROXY/SOCKS_PROXY/ALL_PROXY/NO_PROXY are honored
  automatically, curl semantics; `:proxy :none` forces a direct
  connection). Stream responses (`:as :stream`) must be finished with
  `http/close!`.
- Transport failures throw `{:type :transport-error}`; errors carry stable
  retryable message tokens ("network error" for connect/DNS/timeout/RST).

## Background work (`kmet.libs.concurrent/spawn`)

Under the SCI host contexts (bb and the JVM, and a `:sci`-only extension on
Jolt) extension code runs without `future`/`pmap`/`pcalls` — SCI is a pure
interpreter with no bundled `Executor`, while `babashka` injects `future`
only at the host level (`sci/init {:namespaces {'clojure.core {'future …}}}`).
`kmet` deliberately does **not** forward it; use the whitelisted helper
`kmet.libs.concurrent/spawn` (the same daemon-`Thread` helper the shipped
adapters already copied):

```clojure
(ns my-ext.main
  (:require [kmet.libs.concurrent :as concurrent]))

(concurrent/spawn (fn [] (try (do-work) (catch Throwable _ nil))))
;; => Thread (started, daemon=true); exceptions swallowed, interrupt/join via the returned Thread
```

- Whitelisting `future` would pull in an implicit global pool, let extension
  work survive `unload-extension!`/`reload-extensions!` and keep `kmet`
  alive past shutdown, enable unbounded submission with no backpressure, and
  need the rest of the family (`future-call`/`future-cancel`/`pmap`/`pcalls`)
  for consistency. An explicit daemon `Thread` keeps the lifecycle obvious
  and keeps unload/reload clean. On Jolt's native loader there is no
  interpreter in the way — the extension runs in the runtime — so this is a
  convention there rather than an enforced absence (a `:sci` fallback
  context on Jolt has the same absence as bb); the lifecycle reasoning is the
  same, because `unload` still cannot stop work you started. Keep `Thread`
  use through `kmet.libs.concurrent/spawn` — don't construct `Thread.`
  directly in extensions.

## Building UI

Extensions that contribute interactive components (`ui/custom`) build them
with `kmet.tui.*` — the same pi-tui-derived component library the kmet UI
itself is built on. The namespaces are shared **by reference**: extension
components are the same `IComponent` records the host renders, so keys,
focus, theming and caching behave identically, and there is nothing to
reimplement.

```clojure
(ns my.dialog
  (:require [kmet.tui.protocols :as protocols]
            [kmet.tui.components.container :as container]
            [kmet.tui.components.text :as text]
            [kmet.tui.theme :as theme]
            [kmet.libs.hikap.component :refer [track!]]
            [kmet.tui.macros :refer [defcomponent]]))

(defcomponent MyDialog [this width]
  (protocols/render [_ w]
    (track! this w)
    (container/->Container
     {:child (text/->Text "Hello from an extension")})))
```

What is available: the core protocol records (`container`, `box`, `text`,
`spacer`, `markdown`, `input`, `editor`, `editing`, `select-list`,
`settings-list`, `filter-panel` (the filterable list panel the simple
selectors share — the caller supplies rows, filtering and chrome), `stack`,
`v-stack`, `h-stack`, `expandable-text`, `image`,
`spinner`, ...), `kmet.tui.theme` (styling — including
`theme/get-settings-list-theme` for the selector look),
`kmet.libs.hikap.component` (`track!`/`track-deps` reactive caching,
`with-let`), `kmet.tui.macros` (`defcomponent`), `kmet.tui.keybindings`, `kmet.tui.keys`,
`kmet.tui.fuzzy` and `kmet.tui.autocomplete`. Extension components must follow
the duck-typed `{:render :handle-input :invalidate}` contract accepted by
`ui/custom` (returning a real record is also fine). Extension components are
weakly watched: the `track!` and reaction registries hold them weakly, so a
component nothing references is reclaimed by the render loop's sweep even
when a drop site forgets `dispose`. That backstop is not a contract —
`ui-set-widget` replacement, dialog close and `/reload` dispose
deterministically, and anything GC cannot release (timers, subprocesses,
listeners, `with-let` cleanups) still requires `dispose`. A `kmet.tui.*` require that
is not part of the shared set (or a `kmet.agent.*`/`kmet.agent.modes.*`/`kmet.libs.*`
require) fails the load with an explicit error. The loader
(`kmet.loader*`) is host machinery — never part of the shared set, so
requiring it fails like any other internal.

Mount the component with `ui-custom` (the pattern and its options are in the
contract reference's § UI, and
[`extensions/README.md`](../extensions/README.md#building-ui) documents it
from the shipped extensions' side).

## Testing an extension

Extensions are testable in isolation with the **nullable API** — a test
fixture that captures every registration into a state atom, with no kmet
runtime involved. Load the extension's `init` against it and assert what was
registered (the state shape and the `create-nullable-api` contract are in the
contract reference's § Testing extensions).

The fixture in a directory-based extension's own test tree:

```clojure
(ns my.hello-ext-test
  (:require [clojure.test :refer [deftest is]]
            [kmet.extension :as ext]
            [my.hello-ext :as sut]))

(deftest init-registers-hello-command
  (let [{:keys [api state]} (ext/create-nullable-api)]
    (sut/init api)
    (is (contains? (:commands @state) "hello"))))
```

Run those tests from inside the extension directory against its own deps
(`bb test`), or from the repo root — extension test trees are part of the
`bb test` manifest. For runtime integration tests, `load-extension!` +
`unload-extension!` against the real registries (see
`modules/agent/test/kmet/agent/extensions/`).

Single-file extensions have no test tree of their own: validate changes by
loading the file through `load-extension!` or against
`kmet.extension/create-nullable-api`.

## Worked example

A complete extension showing the common pieces:

```clojure
(ns my.ext
  "Example: command + tool + event handler + status."
  (:require [clojure.string :as str]
            [kmet.extension :as ext]))

(defn init [api]
  (ext/ui-set-status api "my-ext" "loaded")

  (ext/register-command! api
    {:name "uppercase"
     :description "Uppercase a string"
     :handler (fn [ctx args] (str/upper-case args))})

  (ext/register-tool! api
    {:name "my-echo"
     :description "Echo the text argument"
     :params {:text {:type :string :description "Text to echo"}}
     :execute (fn [args] {:content (str "echo: " (:text args))})})

  (ext/on-event api :agent-end
    (fn [ev ctx] (ext/ui-notify api "Turn finished" :info)))

  (ext/on-input api
    (fn [{:keys [text]}]
      (when (str/starts-with? text "!magic")
        {:action :transform :text (str "The magic word is " text)}))))

(defn shutdown [api]
  (ext/ui-set-status api "my-ext" nil))
```

More complete extensions to read: `extensions/tools.clj` (a command's
persisted selection), `extensions/clojure/` (a directory extension with
tools, a skill, and custom renderers), and `extensions/mcp-adapter/` (tool
sources and an overlay panel).
