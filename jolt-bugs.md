# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-12 against Jolt `v0.8.21` (`5b46858a`, still the
latest tag; the #1313 and #1316 fixes merged into `main` after it, verified on
the `v0.8.21-39-g03494c0e` and the installed `v0.8.21-61-g4d665200` dev
builds — the #1313 effect re-measured in [`perf.md`](perf.md) §17, and #1327
filed from the §18 pass) and `jolt-lang/http-client` `v0.1.2`
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
capture maps by ref identity. Re-checked 2026-10-12 on the installed
`v0.8.21-61-g4d665200` dev build (perf.md §17.2): all instances hash
distinctly, the O(n²) assoc is gone, and the many-dep `force-run!` is linear
again — 53.7 µs at 50 deps, 1.66x bb (was 5.5x on the phone), 1.2–1.7x out to
200 deps. A flat per-ref-key assoc cost remains (~5x a keyword key;
`(hash obj)` 0.275 µs against `identityHashCode`'s 0.044) — understood and not
an action item.

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
Remove it and move the floor when the next tag lands. Re-checked 2026-10-12 on
the installed `v0.8.21-61-g4d665200` dev build (perf.md §17.5):
`(io/writer f :append true)`, `(io/reader f :encoding ...)` and
`(io/input-stream f)` all work and append correctly; the `spit` workaround
stays until the floor moves past `v0.8.21`.

### [jolt#1327](https://github.com/jolt-lang/jolt/issues/1327) — DFA search: a late match costs ~60 ns per preceding position

Open, filed 2026-10-12 from the perf.md §18 pass (x86_64, installed
`v0.8.21-61-g4d665200`). `re-find` on a group-free pattern whose match sits
late in the string re-runs the anchored DFA at every start position before
the match — ~60 ns per position, so 24.2 µs for a match at k=400 against the
same pattern's backtracker (one capture group) at 1.62 µs and bb's 2.08.
No-match scans are unaffected (2.0 µs on both hosts). `re-seq`,
`clojure.string/replace` and `Matcher.find` loops pay it once per match; a
styled line whose reset escape sits at the end makes the 2-match
`str/replace` 6.8–8.2 µs against bb's 1.7–2.2. The fix belongs in the DFA's
leftmost-start recovery; a "prefer the backtracker" change would regress the
DFA's no-match scans and patterns like `tool_renderers`'s diff tokenizer.
kmet workarounds: the existing scanner carve-outs (`strip-ansi`,
`visible-width`, `ansi-code-at`) already avoid it on the frame path, and the
§18 pass removed the last two raw uses (`truncate-to-width`,
`wrap-single-line`). The wrap tracker (`ansi-process-text!` /
`ansi-process-range!`) still uses `re-seq`/`re-find` — candidate follow-up.
Do not remove the scanners when the ticket closes: they beat both engines.
