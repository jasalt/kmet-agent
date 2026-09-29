# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Closed tickets and completed work are intentionally omitted.
Status checked against GitHub on 2026-09-29 with `jolt v0.8.14-27-gb96f8615`
installed (head commit `b96f8615`, 2026-09-29T14:51Z).

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

### Unfiled — Windows static-native builds need dependency-aware preload and link flags

Verified on `jolt v0.8.12-24-gfe2a4ab2`, Windows 11, 2026-09-25. Re-read at
head `b96f8615` (2026-09-29): `bld-preload-static-natives!` still preloads
each archive independently, the Windows launcher link line still omits
OpenSSL's CryptoAPI dependency, and `bld-mkdir-p` is unchanged. No open Jolt
PR or issue covers any of the three points.

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

### [jolt#1196](https://github.com/jolt-lang/jolt/issues/1196) — spawned children inherit the parent's ignored SIGPIPE

Verified on `jolt v0.8.14-27-gb96f8615` (2026-09-29). A child spawned through
`babashka.process/process` reports `SigIgn: 0000002000001001` (SIGHUP,
SIGPIPE and a real-time signal, bit 37), and the mask survives `sh -c` into
grandchildren. bb 1.13.224's children get `0000002000000001` — SIGHUP and
the RT signal too, but SIGPIPE reset to the default, as the JVM does for its
children. So `yes e | head -c …` run through a Jolt-host bash tool prints
`yes: standard output: Broken pipe` and exits through the error path, where
on bb `yes` is killed by SIGPIPE; the bytes are otherwise identical and
nothing deadlocks.

No kmet-side workaround is practical: `trap - PIPE` cannot reset it (POSIX
forbids a non-interactive shell from resetting a signal ignored at entry;
verified on Jolt that the mask survives), and `babashka.process` exposes no
pre-exec hook. When Jolt resets SIGPIPE to the default in the child before
exec, nothing in kmet changes — spawned commands simply start dying by
SIGPIPE as they do on bb.

### [jolt#1197](https://github.com/jolt-lang/jolt/issues/1197) — java.time.LocalDateTime factory gaps

Verified on `jolt v0.8.14-27-gb96f8615` (2026-09-29), all three in the
`LocalDateTime` statics in `stdlib/jolt/time/local.clj`:

- `ofInstant` is absent — every call throws `IllegalArgumentException: No
  matching field or method: java.time.LocalDateTime/ofInstant`, for
  `ZoneId/systemDefault`, `ZoneId/of` and `ZoneOffset/UTC` alike.
- `ofEpochSecond` ignores its offset argument (`(fn [secs nano _off] …)`):
  with `ZoneOffset/ofHours 2` it returns the UTC-based local time, where
  the JVM applies the offset. (It also accepts a ZoneId, which the JVM
  rejects by signature.)
- `of` has no `Month`-taking arity: `java.time.Month/SEPTEMBER` throws
  `ClassCastException`; the JVM overloads accept it.

The equivalent chain works on Jolt: `(-> (Instant/parse ts) (.atZone zone)
(.toLocalDateTime))`, as do `ZonedDateTime/ofInstant`, `LocalDateTime/now`
and `LocalDateTime/parse`. kmet's tree selector formatted label timestamps
through `ofInstant`; it now uses the chain. The old call site's catch had
swallowed the exception (labels silently showed the raw ISO timestamp on
Jolt); the tree-selector test now pins the formatted output. Recheck when
the issue is fixed; the workaround is behavior-identical on both hosts.
