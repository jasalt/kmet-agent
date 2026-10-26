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

`--patch FILE` applies a **layer** after the profile's own rows. A layer is
plain EDN — the same shape the built-in profiles use:

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
  keys `:name :inject :provide :external` if the replacement needs them;
- **remove it** — `{:id :tools :remove true}`.

A row without an `:id` in `:patch` is a **new row**: it takes `:after`/`:before`
to say where it goes (or appends), and must carry a `:plugin`. Its `:id`
defaults to the plugin's name.

Every mistake is refused by name before anything boots: an unknown row id, a
duplicate id, a patch that changes nothing, a `:remove` that also sets config,
an unknown patch key, or a file that is not `{:rows … :patch …}` — each names
the offending entry (`:kmet.app/unknown-row`, `:kmet.app/bad-patch`,
`:kmet.app/bad-patch-file`, …).

Repeat `--patch` to stack layers; they apply in the order given, so a later
file can edit what an earlier one inserted.

## Checking a layer before you run it

`--dump-config` composes and prints without booting, so it is the cheap way to
see what a layer did:

```sh
kmet --dump-config --patch ./layer.edn | grep my-service
kmet --dump-state  --patch ./layer.edn      # …and what the boot made of it
```

`--dump-config` and `--dump-state` are separate dumps; passing both is a usage
error rather than a silent pick.

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
