# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-15 against Jolt `v0.8.21` (`5b46858a`, still the
latest tag; every listed fix — #1313, #1316, #1327 and #1364 — has since
merged into `main` after it, verified on the installed
`v0.8.21-205-gd7dc7972` dev build; #1313 was re-measured in
[`perf.md`](perf.md) §17, and #1327 filed from the §18 pass) and
`jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged; `HEAD` is that same
revision), the versions now pinned by the project. The #1316 and #1364 kmet
workarounds were removed 2026-10-15, ahead of the floor move they were gated
on; `:jolt/min-version` still names `0.8.21`, so until the next tag a source
build wants a Jolt from `main` carrying #1316 and #1364.

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
silently produces nothing on Jolt. kmet-side workaround: those logs wrote
through `spit` (commit `c56e80f7`, cherry-picked from `status-fast-path`); it
was removed 2026-10-15 ahead of the tag, so `kmet.tui.core/append-log!` and
`kmet.libs.terminal`'s `write-log!` / `input-log!` take `io/writer :append`
again and a Jolt below the release carrying #1316 silently drops those logs.
Re-checked 2026-10-12 on the installed `v0.8.21-61-g4d665200` dev build
(perf.md §17.5): `(io/writer f :append true)`, `(io/reader f :encoding ...)`
and `(io/input-stream f)` all work and append correctly.

### [jolt#1327](https://github.com/jolt-lang/jolt/issues/1327) — DFA search: a late match costs ~60 ns per preceding position

Closed 2026-10-12 by [PR #1330](https://github.com/jolt-lang/jolt/pull/1330)
(merge `26cd9cd0`, on `main` after the pinned `v0.8.21`), which probes the
start positions before the anchored DFA retry; the entry stays live until a
tag carries it. Filed 2026-10-12 from the perf.md §18 pass (x86_64, installed
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
`visible-width`, `ansi-code-at`) avoid it on the frame path, the §18 pass
removed the last two raw uses (`truncate-to-width`, `wrap-single-line`), and
the §20 pass moved the wrap tracker (`ansi-process-text!` /
`ansi-process-range!`) and `split-long-word`'s walker onto the scanners. No
raw `ANSI-CODE-RE` use is left on the frame path. Do not remove the scanners
when the ticket closes: they beat both engines (the tracker scanner is ~10x
the regex for a match at the end of a styled line).

### [jolt#1364](https://github.com/jolt-lang/jolt/issues/1364) — `Reader.read(char[], off, len)` blocks until the buffer is full

Closed 2026-10-15 by [PR #1366](https://github.com/jolt-lang/jolt/pull/1366)
(merge `df265b20`, on `main` after the pinned `v0.8.21`); `read(char[], off,
len)` returns the available characters, `ready()` is honest on an empty
source and `read(CharBuffer)` works — all verified on the installed
`v0.8.21-205-gd7dc7972` — but the entry stays live until a tag carries it and
the floor moves. Filed 2026-10-14 from the SSE idle-reader work on the
installed `v0.8.21-184-gd17f10f9` (`d17f10f9`). Jolt's `char-reader` fills the
destination with blocking `get-string-n!` calls and treats a short read as
EOF, so `Reader.read(char[], off, len)` over a pipe, socket or terminal does
not return the characters already available; the JVM contract is "block
until at least one character is available, then return what is available"
(the byte `in-stream.read` already follows it, and the source comment on
`char-reader-fill-string!` records that the char side never did).
`Reader.ready()` on the same host is hardcoded `#t` — true on an empty
source — and `read(CharBuffer)` is missing, throwing
`ClassCastException: ja-len … HeapCharBuffer … is not of type …`; both
findings ride the ticket. Consequence on master: the SSE idle reader read
char chunks through a `Reader`, so a slow-but-alive stream appeared stalled
and the per-byte idle timeout fired on Jolt (babashka/JVM behaved). kmet
workarounds in `src/kmet/libs/sse.clj`: InputStream bodies took the chunked
byte-reader path with UTF-8 line assembly, and Reader bodies filled one char
per read on Jolt (`make-char-idle-reader`). Both — and the `kmet.libs.host`
require they alone needed — were removed 2026-10-15 ahead of the tag: the
line reader goes through `io/reader` again, so on a Jolt below the release
carrying #1364 a slow stream's char read blocks to a full chunk and trips
the idle timeout. The slow idle tests
(`kmet.ai.test-sse/test-openai-stream-idle-timeout`,
`test-openai-stream-idle-resets-on-flow`) and `kmet.libs.test-sse` cover the
behavior on both hosts.
