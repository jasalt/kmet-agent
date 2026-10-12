# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-12 against Jolt `v0.8.21` (`5b46858a`, still the
latest tag; the #1313 and #1316 fixes merged into `main` after it, verified on
the `v0.8.21-39-g03494c0e` dev build) and `jolt-lang/http-client` `v0.1.2`
(`00455b8`, unchanged; `HEAD` is that same revision), the versions now pinned
by the project.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open, as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself. It
affects extensions that choose the SCI loader backend on Jolt and copy or
implement host protocols; shipped extensions using Jolt's native loader are
not blocked. `jolt/deps.edn` pins that SCI revision for the `:sci` fallback.
Keep the pin until the change is released by Jolt.

### [jolt#1313](https://github.com/jolt-lang/jolt/issues/1313) — constant `hash` for modeled/reify objects

Closed 2026-10-11 by [PR #1314](https://github.com/jolt-lang/jolt/pull/1314)
(merge `7df2813f`, on `main` after the pinned `v0.8.21`), which hashes
objects without a user-defined hash by identity; the entry stays live until a
tag carries it. Filed 2026-10-11 from the v0.8.21 re-check (perf.md §16.2).
`hash`/`.hashCode`
of modeled-class instances (`Atom`, `Delay`, `Volatile`, `Object`, …) and
`reify` objects returns one constant for every instance where the JVM falls
back to the identity hash, so maps/sets keyed by refs degrade to one collision
bucket — O(n²) to build. Measured in `kmet.libs.reakt`'s per-run dependency
maps (a 50-dep `force-run!` is 568 µs jolt vs 103 µs bb); the cost is paid on
every ref-keyed capture map, negligible for the usual handful of deps and
dominant in the many-dep case. Correctness is unaffected. No kmet workaround
applied, so there is nothing to remove when the floor moves; the kmet-side
mitigation, if the single-bucket cost matters on `v0.8.21`, is to stop keying
capture maps by ref identity.

### [jolt#1316](https://github.com/jolt-lang/jolt/issues/1316) — `clojure.java.io/writer`/`reader`/`input-stream` reject the `:append` / `:encoding` opts

Closed 2026-10-11 by [PR #1320](https://github.com/jolt-lang/jolt/pull/1320)
(merge `46d20ae2`, on `main` after the pinned `v0.8.21`), which gives the
openers Clojure's option arities and arglists; the entry stays live until a
tag carries it. Filed 2026-10-11 from the debug-logging work.
`(io/writer path :append true)`
throws `ArityException` on Jolt — only the plain truncating 1-arity form
exists — and `reader`/`input-stream` have the same gap for `:encoding`, so an
append-mode writer cannot be opened at all. `(io/output-stream path :append
true)` *does* work and appends correctly, so the opts plumbing exists and the
arity is what is missing on the other three; `clojure.core/spit` with
`:append`/`:encoding` works on both hosts. Consequence on master: the
append-based log helpers (`kmet.tui.core/append-log!` and the write/input logs
in `kmet.libs.terminal`) swallow the throw, so `KMET_TUI_DEBUG`-style logging
silently produces nothing on Jolt. kmet-side workaround: write those logs with
`spit` (`kmet.tui.core/append-log!` and `kmet.libs.terminal`'s `write-log!` /
`input-log!`, commit `c56e80f7`, cherry-picked from `status-fast-path`).
Remove it and move the floor when the next tag lands.
