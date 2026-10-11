# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-11 against Jolt `v0.8.21` (`5b46858a`, the latest
tag) and `jolt-lang/http-client` `v0.1.2` (`00455b8`), the versions now pinned
by the project.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open. It affects extensions that choose the SCI loader backend on Jolt
and copy or implement host protocols; shipped extensions using Jolt's native
loader are not blocked. `jolt/deps.edn` pins the SCI revision from
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) for the `:sci`
fallback. Keep the pin until the change is released by Jolt.

### [jolt#1313](https://github.com/jolt-lang/jolt/issues/1313) — constant `hash` for modeled/reify objects

Filed 2026-10-11 from the v0.8.21 re-check (perf.md §16.2). `hash`/`.hashCode`
of modeled-class instances (`Atom`, `Delay`, `Volatile`, `Object`, …) and
`reify` objects returns one constant for every instance where the JVM falls
back to the identity hash, so maps/sets keyed by refs degrade to one collision
bucket — O(n²) to build. Measured in `kmet.libs.reakt`'s per-run dependency
maps (a 50-dep `force-run!` is 568 µs jolt vs 103 µs bb); the cost is paid on
every ref-keyed capture map, negligible for the usual handful of deps and
dominant in the many-dep case. Correctness is unaffected. No kmet workaround
applied — the kmet-side fix, if the ticket stalls, is to stop keying capture
maps by ref identity.
