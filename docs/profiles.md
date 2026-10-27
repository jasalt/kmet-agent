# Profiles and composition

A profile is the set of services a kmet run mounts. `--profile` picks one; the
default is `tui`.

```sh
kmet                                   # the TUI (--profile tui)
kmet --profile print "list the files"  # one prompt, then exit
kmet --profile rpc                     # EDN command stream on stdin/stdout
kmet --profile server --port 7777      # the same protocol on a socket, one agent per connection
```

`--mode` is the older spelling of the same switch: `--mode rpc` runs the rpc
profile, and `--mode text` / `--mode edn` run the print profile and choose its
output — `-p`/`--print` is `--mode text`.

## What a run mounts

Every profile composes the same kind of thing: **rows**. A row is one service —
the extension host, the event bus, the tool registry, the session log, the
agent factory, the system-prompt builder, the mode that runs the whole thing —
and the row list is what a boot mounts, in order.

`--dump-config` prints that list without booting anything:

```sh
kmet --dump-config
```

```
profile :tui rows=14 patches=1
:extensions           kmet.app.extensions/plugin       inject={} provide=[] config={}
:events               kmet.app.events/plugin           inject={} provide=[:events] config={}
…
:agent-loop           kmet.app.loop.driver/plugin      inject={:agents nil, :projections nil, …}
:mode-tui             kmet.ui.tui/plugin               inject={:system-prompt nil, :tools nil} …
```

Each line names the row id, the namespace that provides it, what the row
injects from other rows, what it provides, and its config. `--dump-state`
answers the other question — what a boot actually built: it boots the same
rows and prints the ctx tree, with each fiber's state, what it provides and
what it is waiting for.

## Patching a composition

A **layer** is plain EDN — the same shape the built-in profiles use. Two files
(below) apply a layer to every profile, and `--patch FILE` adds one to a
single run:

```clojure
{:rows  [{:id     :my-service
          :plugin my.extension/service-row}]   ; a row to insert
 :patch [{:id :tools :remove true}             ; and edits to existing ones
         {:id :agentLoop :config {:max-steps 8}}]}
```

A patch entry addresses a row by `:id` and does one of three things:

- **change its config** — `{:id :agentLoop :config {...}}` deep-merges into the
  row's config;
- **replace its source** — `{:id :tools :plugin my.ns/plugin}`, with the card
  keys `:name :inject :provide` if the replacement needs them;
- **remove it** — `{:id :tools :remove true}`.

A row without an `:id` in `:patch` is a **new row**: it takes `:after`/`:before`
to say where it goes (or appends), and must carry a `:plugin`. Its `:id`
defaults to the plugin's name.

### Your own layers

Two files apply to every profile, so a run needs no `--patch` flag to carry
them:

1. `~/.kmet/agent/patch.edn` — global (`KMET_CODING_AGENT_DIR` relocates it);
2. `.kmet/patch.edn` — project-local, resolved against the current directory.

A missing file is not a layer; an existing one is read and validated like a
`--patch` file, so a malformed file fails the composition by name rather than
being ignored.

The application order is **built-in table, global, project, then each
`--patch` flag in the order given**, so a project layer overrides a global one
and a `--patch` flag overrides both. This is kmet's usual scope order (project
over user), applied to composition; [`new-app.md`](../new-app.md) records the
difference from dsh, whose home-level patch is applied after the profile's own
and no project layer exists.

A project layer is project code: a row names a plugin var or an installed
`:ext/<id>`, so a `.kmet/patch.edn` in a cloned repository can mount code at
boot. kmet has no project-trust prompt (project settings and resources load
unconditionally, as in pi), so review the file like any other project
configuration before running in that directory.

### Extensions as rows

A layer can name an installed extension by id instead of a built-in plugin
var:

```clojure
{:rows [{:plugin :ext/grep-tool        ; the installed extension's id
         :config {:roots ["src"]}}]} ; reaches the extension as (:config api)
```

The id is the manifest `:name`, or a single file's basename without `.clj`
(`grep-tool.clj` is `:ext/grep-tool`). Resolution reads the installed set the
world loader reads — top-level settings paths, `~/.kmet/agent/extensions` and
`.kmet/extensions`, packages, and the bundled artifacts — without loading
anything, so `--dump-config` names the row and an unknown id is a composition
error before boot. The first copy in pi precedence wins, so a user's copy
shadows a bundled one.

Naming an extension is the intent: the row loads it even when
`:extensions`/`:bundled-extensions` do not enable it, as long as the artifact
is installed. The row must mount after the `:extensions` host row (the first
row of `:bundle/minimal`, which an appended layer already follows); disposing
the row unloads the extension. An extension the composition names does not
load twice — the world loader skips a path already in the registry — so
settings remain the path for everything a profile does not name.

The extension's own plugin still mounts under the host ctx, so an `:ext/…` row
belongs to a root profile, not an agent preset. Tool selection is not a row yet:
`:default-tools` still lives in `settings.edn`.

A row-mounted extension initializes during boot, before the process world loads
its provider catalogs and `models.edn`; the world then recomposes provider
registrations, so registering one is safe, but reading the catalog at init is
not (a settings-loaded extension runs after it).

Every mistake is refused by name before anything boots: an unknown row id, a
duplicate id, a patch that changes nothing, a `:remove` that also sets config,
an unknown patch key, or a file that is not `{:rows … :patch …}` — each names
the offending entry (`:kmet.app/unknown-row`, `:kmet.app/bad-patch`,
`:kmet.app/bad-patch-file`, …).

## Checking a layer before you run it

`--dump-config` composes and prints without booting, so it is the cheap way to
see what a layer did:

```sh
kmet --dump-config | grep my-service
kmet --dump-config --patch ./layer.edn | grep my-service
kmet --dump-state  --patch ./layer.edn      # …and what the boot made of it
```

`--dump-default-config` prints the shipped table alone — no global or project
layer, no `--patch` — which is what to diff against when you want to see what
your files changed:

```sh
diff <(kmet --dump-default-config) <(kmet --dump-config)
```

`--dump-config`, `--dump-default-config` and `--dump-state` are separate dumps;
passing more than one is a usage error rather than a silent pick.

## Profiles this build ships

| profile | what it does |
|---|---|
| `tui` | the interactive terminal UI (the default) |
| `print` | runs one prompt and exits; `--mode text` streams the answer, `--mode edn` prints the session header and events as EDN |
| `rpc` | the long-lived EDN protocol on stdin/stdout — see [`rpc.md`](rpc.md) |
| `server` | the same protocol on a socket, one agent per connection; `--port` (0 asks the OS) or `--socket`, a bearer token, `--max-sessions` — see [`serve.md`](serve.md) |

A profile whose mode row a layer removed is not an error: the boot mounts the
remaining rows, reports what it mounted, and exits, which is how you inspect a
composition without running an agent.
