# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Older closed tickets and completed work are intentionally omitted; the
closures from the last check are kept because they changed kmet's side.
Status checked against GitHub on 2026-09-25 with `jolt v0.8.12-33-ga6e881b1`
installed (head commit `a6e881b1`, 2026-09-25T14:20Z, which postdates every
fix named below).

## Open

Each entry below records the kmet-side workaround (if any) and exactly what
to remove or update when the upstream fix lands.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

The issue affects extensions that choose the SCI loader backend on Jolt and
copy/implement host protocols. Shipped extensions using Jolt's native loader
are not blocked. The fix is proposed in
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093), still open at
head `1295142f`. `jolt/deps.edn` pins that SCI revision for the `:sci`
fallback; keep the pin until the change is released by Jolt.

### [jolt#1142](https://github.com/jolt-lang/jolt/issues/1142) — `mapv`/`filterv` can park a fiber while its carrier holds a counted lock

Filed 2026-09-25, measured on 0.8.12: multi-collection `mapv`, `filterv` and
`(vec (for …))` realize a lazy seq, so a function that parks (an `ebb` sleep,
or waiting on a contended lock) inside one raises `a fiber cannot leave the
CPU while its carrier holds a counted lock`. One-collection `mapv`, `reduce`,
`run!` and `(into [] (map f) xs)` are unaffected.

Nothing in kmet hits it today: the only multi-collection `mapv` is
`(mapv max natural min-cols)` in `kmet.tui.components.markdown` (pure), every
`filterv` predicate reads in-memory data, and kmet's `locking` critical
sections (`kmet.libs.jsonrpc`, `kmet.tui.wake`, the shared context cache) only
`swap!` or `deliver` without parking. It is a constraint for new Jolt code
rather than a bug to fix: keep parking work out of the lazy seq, or realize it
eagerly first.

### [jolt#1191](https://github.com/jolt-lang/jolt/issues/1191) — `Socket.setSoTimeout`/`getSoTimeout` are missing

Filed 2026-09-29, observed on master `c2c5b2c0`. `jolt.socket` implements
neither accessor, so JVM code that bounds a blocking read or accept with
`Socket.setSoTimeout` fails at the setter, and a peer that never answers
blocks the read indefinitely (POSIX fds are `O_NONBLOCK`, so the wait is a
poller park and `SO_RCVTIMEO` cannot fire).
`java.net.SocketTimeoutException` is registered in the class hierarchy but
nothing can raise it.

Affects `kmet.extensions.clojure.nrepl`: its per-read eval deadline is a
no-op on Jolt until this lands — the deadline is still re-checked between
messages, but a read blocked on a silent server waits for the response
(an explicit cancel still closes the socket and unblocks it). The
timed-wait seam this needs is the same one
[jolt#1192](https://github.com/jolt-lang/jolt/issues/1192) (connect timeout)
needs — fix them together. jolt#1192 also covers the related open divergence
that `connect` ignores its timeout argument
(`test/conformance/known-divergences.edn`).

**Workaround:** `kmet.extensions.clojure.nrepl` guards both `.setSoTimeout`
calls in `try`/`catch` — the connect-time setter in `connect-socket` and the
per-read renewer in `renew-read-timeout!` — and documents the deadline as
best-effort in its namespace header and in `extensions/clojure/README.md`
(the `clojure_eval` feature list). When the fix lands: drop both guards,
update those notes to say the deadline holds on both hosts, and let a setter
failure surface instead of being swallowed.

### [jolt#1192](https://github.com/jolt-lang/jolt/issues/1192) — `Socket.connect(endpoint, timeout)` ignores its timeout

Filed 2026-09-29, observed on master `c2c5b2c0`. The 2-arity accepts a
timeout, binds it as `_timeout`, and waits on the poller with no deadline
(`stdlib/jolt/socket.clj` `connect-fd!`), so a connect to a blackholed or
slow endpoint blocks until the OS gives up. Upstream's own divergence row
(`test/conformance/known-divergences.edn`) records it and can retire with
the fix.

kmet does use this arity: `kmet.extensions.clojure.nrepl` passes its
timeout to `(.connect socket endpoint timeout)`, so on Jolt a connect to an
unreachable nREPL server can stall before any read happens. The reason to
track it beside jolt#1191 is shared work: both need a timed
`jolt.io-poller/wait-ready` (kqueue/epoll timeout plus the thread path)
while keeping the untimed path's "never a timed poll" property. Landing
#1191 first makes #1192 a small follow-up.

**Workaround:** none — `connect-socket` already passes its budget to the
3-arity. When the fix lands: only the comment there and this entry change;
the `:nrepl-connect` error then arrives after the configured budget instead
of after the OS gives up.

### Unfiled — Windows static-native builds need dependency-aware preload and link flags

Verified on `jolt v0.8.12-24-gfe2a4ab2`, Windows 11, 2026-09-25. No open Jolt
PR or issue covers any of the three points, and none of them has landed in the
installed `v0.8.12-33-ga6e881b1`.

Jolt preloads each `:static {:archive …}` independently, so `libssl.a` cannot
resolve the `libcrypto.a` symbols during its build-time preload DLL. Its
Windows launcher link also omits OpenSSL's CryptoAPI dependency, and a
drive-rooted missing `<out>.build` reached `bld-mkdir-p` with a non-string
parent. kmet's `kmet.tasks.build-jolt` works around all three by precreating
the build dir, prepending a build-only `cc` shim, and staging static
lz4/zlib in the native directory. The final executable was smoke-run without
the OpenSSL/lz4 DLLs and its PE imports contained Windows system DLLs only.
Recheck when filing upstream; remove the shim once Jolt handles dependent
native archives and their link libraries directly.

## Closed since the last check

kmet's side is kept as it is until it is checked on Windows: no workaround is
removed, and no note is rewritten, on the strength of an upstream fix alone.

- **[jolt#1110](https://github.com/jolt-lang/jolt/issues/1110)** (Windows
  `File`/`Path` separators, `fs/glob`) — closed 2026-09-24 by
  [jolt PR #1124](https://github.com/jolt-lang/jolt/pull/1124). `File` and
  `Path` now render `\` on Windows like the JVM. `kmet.app.extensions`'s
  `own-source-entries` (`#?(:jolt ...)`) still replaces `\` with `/` in
  `:rel`; on a build that carries the fix that replace should be a no-op. It
  stays until a Windows run checks the same tree both ways — with and without
  the replace — and the result is recorded here.
- **[jolt#1127](https://github.com/jolt-lang/jolt/issues/1127)** (the
  misleading `required native library ssl not found` warning) — closed
  2026-09-24 by
  [jolt PR #1129](https://github.com/jolt-lang/jolt/pull/1129), which reports
  the real load reason and finds the exe directory via `GetModuleFileNameW`.
  kmet's default Windows `dist` artifact was already immune; `jolt dist
  --dynamic` is no longer warned off by its own exe directory.
