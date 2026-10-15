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
stream; the `user-out-stream?` arm passes the stream. Workaround:
`kmet.libs.process/write-stdin!` writes UTF-8 bytes with a direct flush on the
process `:in` stream, and `kmet.modes.rpc-client` deliberately does not wrap
`:in` in an `io/writer`. Once the fix reaches a tagged release, the client can
hold `(io/writer (:in p))` again and `write-stdin!` can go.

### [jolt#1380](https://github.com/jolt-lang/jolt/issues/1380) — `io/reader` cannot coerce `*in*`

Jolt's `*in*` is a `clojure.core/IReader` reify, not a `java.io.Reader`, and
the `jolt-io-reader` overlay has no arm for it, so the ordinary JVM way to
read stdin — `(with-open [r (io/reader *in*)] (line-seq r))` — throws
`IllegalArgumentException: Cannot open <#object[clojure.lang.IObj$reify__0]>
as a Reader`. (`clojure.core/read-line` works; only the coercion does not.)
Workaround: `kmet.modes.rpc/serve-input` reads the default input with
`clojure.core/read-line` and wraps only an explicit `:in` override. Once the
fix reaches a tagged release, that branch can go.
