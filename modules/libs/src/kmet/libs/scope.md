# kmet.libs.scope — retired; the store's conventions

`kmet.libs.scope` was the store: a per-node registry of registrations with
owner-scoped, LIFO disposal, and the node's parent/child lifetime. Landing 4e
deleted it, its backend (`kmet.agent.registry-store`) and its tests; the app
runs on the ctx tree instead (`modules/srce/src/kmet/srce/srce.md`,
`new-app.md`).

This file stays because a few dozen docstrings and comments in kmet1's remaining
modules cite its sections for the **conventions** that outlived the code. Each
section below states the rule that survived and what implements it now; what
died with the module is marked as such.

## 1. The store — gone, but its two guarantees are the registry's

The store held one registration per `(owner, key)`; re-registering replaced it,
and `deregister!` was value-guarded, so a stale call removed nothing. That is
the `Registry` contract (`modules/app/src/kmet/agent/registry.clj`): an entry is
addressed by tag and name, `register!` returns a disposer that removes exactly
the entry it installed, and re-registering the same `(tag, name, owner)`
replaces. The atom in the middle is an implementation detail; the contract is
the protocol.

## 2. Owner paths and keys

Owner paths are still the address: `[:app :extensions "name"]`, `[:app :tools]`,
`[:app :session]`. Ownership is what makes unload one call, and an owner holds
whatever several tags it registered under.

Keys are not. A store key (`[:kmet/command "deploy"]`, `[:kmet/hook :turn-end
0]`) was the store's vocabulary for "which registry, which entry"; a registry
now owns its tag and its names, and `deregister-owner!` walks tags instead of
matching key heads.

## 3. Disposal

The rules survived with the code: an owner's registrations unwind in reverse
registration order, a throwing cleanup is reported and never skips the rest, and
disposal never waits — quiescence is the caller's decision, at the
`:agent-settled` boundary.

What implements them is the kernel's effects (`srce/fiber`: a registration's
disposer tied with `srce/effect` unwinds with its fiber, `dispose!` in reverse)
and `kmet.agent.app-runtime/dispose-deregisters!` for what an extension recorded
outside any registry. The `*error-reporter*` binding is gone with the store: the
failure goes to `kmet.agent.debug/log-error` directly, and there is no
"store closed for the pass" refusal — a fiber's disposal is its own boundary.

## 4. Reading order

Both halves survive, unchanged in intent:

- **Display order is first-registration order.** Owners are searched in that
  order, so the owner that took a name keeps it; `entries` lists winners first
  and `effective-entries` collapses to one entry per name, so listing and lookup
  agree.
- **A read is one capture.** The loop reads its stage map once per attempt
  (`:loop-stages` on the ctx chain), so a mid-run change reaches the next
  attempt, never the one in flight. The request's tool list is captured the same
  way: the wire list, the declared set and the dispatch resolve against one
  snapshot, so a tool unloaded mid-request still runs for the request that was
  offered it, and a tool added mid-request is not dispatched.

## 5. Nodes — gone; the ctx tree took their place

A node was a value with a store atom, a `:parent` and `:children`, and host
fields hung on it. A **ctx** is that and the service namespace too: `srce/root`,
`srce/extend` (visible reads, inherited `:fiber`), `srce/isolate` (a private
realm), `srce/mount`, and `srce/detach!` for the lifetime a plain child had no
way to end. A nil root was the process-wide scope; a ctx has no such shortcut —
`srce/inside?` answers subtree membership for an event scope, and the nearest
value wins on the chain.

## 6. The host's view

The app root is `kmet.agent.app-runtime/ctx`, created (composed) before
extensions load. The extension host has been off the store since 4b-ii-a: an
extension's commands, tools, skills, prompts, hooks, renderers, flags, themes and
the UI port are entries in the app's registries under `[:app :extensions
<name>]` (or `[:app :themes]`, `[:app :ui]`), so unloading one is one
`deregister-owner!` per tag plus the fns it recorded for what lives outside any
registry (aliases, shortcuts, model providers, event subscriptions), and an
`:inject` extension's mount is a fiber whose disposal is the kernel's.

`/units` used to walk the root node; it renders the ctx tree now
(`kmet.app.inspect`), and `--dump-state` does the same for a composition it
boots.

The loop's stages are plain data on the root ctx under `:loop-stages`:
`{:loop-guard {:call …} :retry-policy {:call …}}`, with an optional `:config` per
stage. `/reload` re-seats it with `refresh-stages!`; a child ctx
(`app-runtime/child-ctx`) carries its own map and is the per-run isolation
boundary for subagents. The event bus is host-owned state of its own, never a
registration; a subscription may carry a **ctx** scope and then hears events
from that ctx's subtree (`srce/inside?`).

## 7. Namespace rules — gone with the module

The store was rank-0 with no `kmet.*` require and no third-party library,
enforced by `kmet.libs.test-scope-self-contained`; both the require and its
guard died with the file. The kernel's equivalent rules — rank-0
self-containment and no `kmet.agent.*` edge — are `kmet.srce.test-guards` and
`kmet.test-module-deps`.
