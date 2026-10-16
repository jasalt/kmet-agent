# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-15 against Jolt `v0.8.22` (`1db4cc98`, the latest
tag). It carries every fix the v0.8.21 floor had left on `main` — #1313
(PR #1314), #1316 (PR #1320), #1327 (PR #1330) and #1364 (PR #1366) — so
those entries are dropped per the rule above, and the kmet workarounds gated
on #1316/#1364 were already removed 2026-10-15. Also checked:
`jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged; `HEAD` is that same
revision), the versions now pinned by the project. `:jolt/min-version` names
`0.8.22`, so a source build matches the tag. Two further defects (`#1379`,
`#1380`) were found porting the RPC front end (engine.md step 6d) and filed
2026-10-15 against this revision.

Re-checked the same day: both were closed on `main` by
[PR #1382](https://github.com/jolt-lang/jolt/pull/1382) (merge `aaf2dfe1`,
listed under CHANGELOG `[Unreleased]`), verified on the installed
`v0.8.22-15-gaaf2dfe1` dev build, and the kmet workarounds were removed
ahead of the tag. The entries stay live until a tag carries the fixes; until
then a source build wants a Jolt from `main`. `#1031` is unchanged: still
open, as is [babashka/sci#1093](https://github.com/babashka/sci/pull/1093).

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open, as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself. It
affects extensions that choose the SCI loader backend on Jolt and copy or
implement host protocols; shipped extensions using Jolt's native loader are
not blocked. `jolt/deps.edn` pins that SCI revision for the `:sci` fallback.
Keep the pin until the change is released by Jolt.

### [jolt#1379](https://github.com/jolt-lang/jolt/issues/1379) — `clojure.java.io/writer` over an OutputStream loses data

`(io/writer <OutputStream>)` accepts writes, but `.flush` and `.close` never
reach the stream underneath:
the bytes sit in the stream's own port buffer, so a file stream stays empty
and a child process never sees its stdin. The `jolt-io-writer` overlay's
`out-stream?` arm (`host/chez/java/io-streams.ss`) passes `#f` as
`make-charset-writer`'s downstream, so the char-writer's flush/close skip the
stream; the `user-out-stream?` arm passes the stream. Closed 2026-10-15 by
[PR #1382](https://github.com/jolt-lang/jolt/pull/1382) (merge `aaf2dfe1`,
on `main` after the tagged `v0.8.22`). The kmet workaround —
`kmet.libs.process/write-stdin!` writing UTF-8 bytes with a direct flush,
with `kmet.agent.modes.rpc-client` holding the raw `:in` — was removed 2026-10-15
ahead of the tag: the client holds `(io/writer (:in p) :encoding "UTF-8")`
and flushes it per record. The entry stays live until a tag carries the fix;
on the `v0.8.22` floor that wrapper would buffer.

### [jolt#1380](https://github.com/jolt-lang/jolt/issues/1380) — `io/reader` cannot coerce `*in*`

Jolt's `*in*` is a `clojure.core/IReader` reify, not a `java.io.Reader`, and
the `jolt-io-reader` overlay has no arm for it, so the ordinary JVM way to
read stdin — `(with-open [r (io/reader *in*)] (line-seq r))` — throws
`IllegalArgumentException: Cannot open <#object[clojure.lang.IObj$reify__0]>
as a Reader`. (`clojure.core/read-line` works; only the coercion does not.)
Workaround: `kmet.agent.modes.rpc/serve-input` read the default input with
`clojure.core/read-line` and wrapped only an explicit `:in` override.
Closed 2026-10-15 by [PR #1382](https://github.com/jolt-lang/jolt/pull/1382)
(merge `aaf2dfe1`); the workaround was removed ahead of the tag —
`serve-input` wraps `(or in *in*)` with `io/reader` on both hosts. The entry
stays live until a tag carries the fix.
