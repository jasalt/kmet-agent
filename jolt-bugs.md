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
