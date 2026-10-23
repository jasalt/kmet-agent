# kmet.libs.scope — the store and its node

`kmet.libs.scope` is the store: a per-node registry of registrations with
owner-scoped, LIFO disposal, and the node's parent/child lifetime. It lives in
`kmet.libs` (rank 0) but is stricter than the rest of the library: no `kmet.*`
require at all and no third-party library; `kmet.libs.test-scope-self-contained`
enforces the first. The composition layer the old cells module carried —
units, entries, resolution backends — was retired; the store and the node are
the surviving contract.

## 1. The store

A node is a map:

```clojure
{:store    (atom {:by-key {} :by-owner {} :teardowns {}
                  :owner-order [] :disposing nil})
 :parent   parent-node-or-nil
 :children (atom [child …])}
```

plus whatever a host hangs on it (the app's node carries `:loop-stages`, an
atom holding the loop's stage map). `register!` is the only writer of a
registration; the store is an atom so writes are imperative while the node
stays the value callers pass around.

A registration is one `(owner, key) → value`. Re-registering replaces it and
moves it to the end of the owner's registrations. `nil` is not a value
(`::nil-registration`). `register!` returns a deregister fn collected onto the
owner; the caller need not keep it — disposing the owner is the cleanup.

A key holds one value per owner. A registry that addresses its entries by name
keys them by that name (`[:kmet/command "deploy"]`); a registry without names
gives each registration its own key (`[:kmet/hook :turn-end 0]`) rather than
sharing one key and rewriting a collection.

## 2. Owner paths and keys

Keys are the host's vocabulary; owners are paths (`[:app :commands]`,
`[:app :extensions "name"]`, `[:app :session]`). Ownership is the address:
nothing else has to exist for a registration to be addressable, and an owner
is disposed as a whole — everything it registered, in LIFO order.

- `register! node owner k v` → deregister fn
- `deregister! node owner k v` — value-guarded, so a stale call is a no-op
- `deregister-key! node k` / `(deregister-key! node k owner)` — remove by key
- `add-teardown! node owner f` — cleanup for what lives outside the store
- `owner-keys`, `owner-registrations`, `ordered-registrations`, `owners`,
  `registrations`, `for-key` — reads

`owners` is first-registration order; `owner-registrations` is that owner's
registration order; `ordered-registrations` is `[key owner value]` across
owners in that order. A deregister fn's value guard has one caveat: two
registrations of an *equal* value are indistinguishable, so an earlier one's
fn can remove the newer one.

## 3. Disposal

`dispose-owner! node owner` runs the owner's registrations in reverse
registration order, then its teardowns in reverse order. The owner is
**closed** for the pass: `register!`/`add-teardown!` under it — or under any
owner during a store-wide pass — throws `::closed-store`; when a cleanup
trips it, the cleanup runner reports it like any other failure. A throwing
cleanup is reported through `*error-reporter*` and never skips the rest. The
refusal is scoped to the pass; the store accepts the next registration
afterwards.

`dispose-store! node` disposes every owner in reverse owner order with the
whole store closed, then resets it. `dispose-node! node` ends children first
(reverse creation order), unlinks the node from its parent's `:children`, then
runs `dispose-store!`. Both are idempotent.

The store has no clock: disposal never waits. Quiescence is the caller's
decision — the agent disposes at the `:agent-settled` boundary.

The default `*error-reporter*` prints to stderr (the namespace must not
require `kmet.*`); `kmet.agent.app-runtime` rebinds it to
`kmet.agent.debug/log-error`.

## 4. Reading the store

Reads are one store snapshot: a registration made while a read runs cannot
split the answer. `ordered-registrations` is the order a host registry lists
in — owners by first registration, each owner's by its own. The host reads
its loop stages **once per attempt** from the node's `:loop-stages`, not per
tool call, so a mid-run change reaches the next attempt, never the one in
flight.

The same discipline covers the request's tool list: the wire list, the
declared set and the dispatch all resolve against the one capture made when
the request was built, so a tool unloaded mid-request still runs for the
request that was offered it — and a tool added mid-request is not dispatched.

## 5. Nodes

`node` builds a node; a non-nil parent gets it in `:children` and sets
`:parent`. `EXTRA` carries host fields; the node's `:store`, `:parent` and
`:children` always win. `in-subtree?` walks parents; a nil root is the
process-wide scope, so every node (nil included) is in it. A node is a value;
the store and the children list are atoms.

## 6. The host's view

The app root is created by `kmet.agent.app-runtime/node` before extensions load;
kmet1's front ends, engines and `/units` read it. Since 4b-ii-a the extension
host **no longer registers into it**: an extension's commands, tools, skills,
prompts, hooks, renderers, flags, themes and the UI port are entries in the
app's registries under `[:app :extensions <name>]` (or `[:app :themes]`,
`[:app :ui]`), so unloading one is one `deregister-owner!` per tag plus the fns
the extension recorded for what lives outside any registry (aliases,
shortcuts, model providers, event subscriptions), and an `:inject`
extension's mount is a fiber whose disposal is the kernel's.

`/units` used to print one flat `<owner> keys=[…]` line per owner that holds
registrations, by walking the app root node; 4d replaced that listing with the
ctx tree renderer (`kmet.app.inspect`), so the store has no listing now — what
is left of it is `registry_store`'s backend, the node the reporter binding hangs
off, and the tests until 4e.

The loop's stages are plain data on the app root **ctx**, under `:loop-stages`:
`{:loop-guard {:call …} :retry-policy {:call …}}`, with an optional `:config`
per stage. The loop reads the map once per attempt; `/reload` re-seats it with
`refresh-stages!`; a child ctx (`runtime/child-ctx`) carries its own map and is
the per-run isolation boundary for subagents. The event bus is host-owned state
of its own, not a store registration; a subscription may carry a **ctx** scope
and then hears events from that ctx's subtree (`srce/inside?`).

## 7. Namespace rules

- Rank 0: no `kmet.*` require at all, no third-party library.
  `kmet.libs.test-scope-self-contained` enforces the require rule.
- The store is an atom, the node a value: callers hold nodes, writes go
  through the functions.
- Failures are reported through `*error-reporter*`, never swallowed, and a
  failing cleanup never skips the rest.
- No clocks, timers or threads in the store; disposal never waits.
