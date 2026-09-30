# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Closed tickets and completed work are intentionally omitted.
Status checked against GitHub and the re-fetched `origin/main` on
2026-09-30, with the local `jolt v0.8.15` build installed (commit
`924d80e9`, 2026-09-29T19:24Z).

## Open

Each entry below records the kmet-side workaround (if any) and exactly what
to remove or update when the upstream fix lands.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (labeled `deferred`; last upstream activity 2026-09-18). It affects
extensions that choose the SCI loader backend on Jolt and
copy/implement host protocols. Shipped extensions using Jolt's native loader
are not blocked. The fix is proposed in
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093), still open at
head `1295142f`. `jolt/deps.edn` pins that SCI revision for the `:sci`
fallback; keep the pin until the change is released by Jolt.

### Windows static-native build gaps

Verified on `jolt v0.8.12-24-gfe2a4ab2`, Windows 11, 2026-09-25; re-read at
`v0.8.15` (`924d80e9`, 2026-09-29), all three unchanged. Filed as separate
tickets because each needs its own fix in `host/chez/build.ss`. kmet's
`kmet.tasks.build-jolt` works around all three by precreating the build dir,
prepending a build-only `cc` shim, and staging static lz4/zlib in the native
directory; the final executable was smoke-run without the OpenSSL/lz4 DLLs
and its PE imports contained Windows system DLLs only. Remove the shim's
parts as the tickets land.

- [jolt#1205](https://github.com/jolt-lang/jolt/issues/1205) — dependent
  archives in the build-time preload: `bld-preload-static-natives!`
  (`host/chez/build.ss:1303`) preloads each `:static {:archive …}`
  independently, so `libssl.a` cannot resolve the `libcrypto.a` symbols
  during its build-time preload DLL.
- [jolt#1206](https://github.com/jolt-lang/jolt/issues/1206) — Windows
  launcher link omits `-lcrypt32`: the `bld-nt?` branch of `bld-link-libs`
  (`host/chez/build.ss:514`) carries `-lws2_32` and `-lz` but no CryptoAPI
  library (no `-lcrypt32` anywhere in build.ss).
- [jolt#1207](https://github.com/jolt-lang/jolt/issues/1207) —
  `bld-mkdir-p` reaches a non-string parent: a drive-rooted missing
  `<out>.build` hit `bld-mkdir-p` (`host/chez/build.ss:71`) through the
  POSIX-only `path-parent` (`host/chez/java/io.ss:832`).

### [jolt#1203](https://github.com/jolt-lang/jolt/issues/1203) — `jolt.loader` mishandles Windows `file:` resource URLs

Verified on `v0.8.15` (`924d80e9`), Windows 11, 2026-09-30. Fix: convert
through `clojure.java.io/as-file` instead of `(subs s 5)`; remove the
standalone-failure note in `windows.md` when it lands.

`jolt.loader/file-url-path` (`stdlib/jolt/loader.clj:350`) strips a `file:`
prefix with `(subs s 5)` and hands the remainder to `io/input-stream`;
`default-open` (`:378`) and `hit-url` (`:391`) both go through it. A Windows
classpath resource URL is `file:/C:/src/…/loader.md`, so the path becomes
`/C:/src/…/loader.md`, which Windows resolves to the invalid `\C:\src\…` and
`io/input-stream` raises `FileNotFoundException … (Invalid argument)`. The
runtime's file-URI conversion (`file-url->path`, correct since v0.8.15)
already handles the shape, so the fix is to convert through
`clojure.java.io/as-file` (or the runtime's `file-url->path`) instead of
`subs`.

kmet sees it as the Jolt-only failure of
`kmet.loader.test-jolt-loader/adapter-host-view-is-the-extension-contract`
(a `:resource` request through the host view; `bb test` passes). No kmet-side
workaround: the failing open runs inside `jolt.loader`, behind the adapter's
`load` delegation. Nothing to remove when it lands — the test passes
unchanged.
