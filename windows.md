# Windows host — outstanding test failures

Recorded 2026-09-29 on Windows 11 26200 in Windows Terminal (`WT_SESSION`
set), Jolt `v0.8.14-29-gc7c6b33e`; re-verified 2026-09-30 on `jolt v0.8.15`
(`924d80e9`). A clean `master` fails these — they are pre-existing
Windows-host issues, not regressions from the Jolt-port validation work,
and every standalone item below still fails as recorded:

- `bb test` — 2730 tests / 17749 assertions, 4 failures + 3 errors (34 s)
- `jolt test` — 2710 tests / 17648 assertions, 1 failure + 4 errors (79 s)

Each item was rerun standalone on both hosts with its plain var name to pin
the host(s):

```sh
bb test <var>     # e.g. bb test with-help-test
jolt test <var>   # whole-ns filters skip ^:bb-only vars; a var filter runs them
```

## Path separators (both hosts)

### `tasks.test-slop/source-discovery-skips-build-dirs-and-other-extensions`
`test/kmet/tasks/test_slop.clj:156` — expects `["src/a.clj"]`, gets
`["src\a.clj"]`: the expectation compares `(str (fs/relativize dir %))`
raw, and `fs/relativize` returns platform separators on Windows. Fails in
both full runs; passes on neither host standalone. Fix: compare through
`kmet.test-utils/slash` (the existing helper for exactly this) or normalize.

### `tasks.build-test/stage-bundled-extensions-embeds-the-artifact-roots`
`test/kmet/tasks/build_test.clj:358` — expects `"target/kmet-bundled"`,
gets `"target\kmet-bundled"` (same raw-separator comparison). `^:bb-only`:
runs in `bb test`, skipped by `jolt test` whole-ns selection, and fails the
same way when forced with a var filter on Jolt.

## POSIX permissions (both hosts)

### `tasks.build-test/install-artifact-copies-the-executable-into-out`
`test/kmet/tasks/build_test.clj:101` — `java.lang.UnsupportedOperationException`
from `babashka.fs/set-posix-file-permissions`
(`tasks/kmet/tasks/build.cljc:541`, `install-artifact!`); the second
assertion then dies on the missing copy
(`java.io.FileNotFoundException: target\test-dist-install\kmet.sh`,
`:114`). `^:bb-only`, so it only appears in `bb test` (and when forced on
Jolt). Fix: guard the chmod with `kmet.libs.host/windows?` (or catch the
unsupported op) in `install-artifact!`, then assert the copies.

### `tasks.build-jolt-test/assemble-copies-the-compiled-binary-to-its-artifact-path`
`test/kmet/tasks/build_jolt_test.clj:229` — same
`UnsupportedOperationException` from the posix-permission call in the Jolt
packager. Appears in **both** full runs (not bb-only). Fix as above.

## bb-only: CRLF from `with-out-str`

### `tasks.test-help/with-help-test`
`test/kmet/tasks/test_help.clj:21` — two assertions expect `"\n"` and see
`"\r\n"`: `(with-out-str …)` yields `summary\r\n` / `RAN\r\n` on bb under
Windows. `jolt test with-help-test` passes (Jolt's output capture does not
translate). Fix: normalize `\r\n` → `\n` in the expected strings (or bind a
writer that does not translate).

## Jolt-only: Windows `file:` resource paths (tracked upstream)

### `loader.test-jolt-loader/adapter-host-view-is-the-extension-contract`
`test/kmet/loader/test_jolt_loader.clj:111` —
`java.io.FileNotFoundException: \C:\src\my\kmet\src\kmet\loader\loader.md
(Invalid argument)`. **Not** the runtime's URI-to-path constructors (fixed
in v0.8.15): it is `jolt.loader/file-url-path` (`stdlib/jolt/loader.clj:350`)
stripping `file:` with `(subs s 5)`, so `file:/C:/…` becomes the invalid
`\C:\…` on Windows. Tracked as
[jolt#1203](https://github.com/jolt-lang/jolt/issues/1203) (`jolt-bugs.md`);
`bb test` passes and there is nothing to fix in kmet.

## Flaky: curl through the test SOCKS proxy (observed on Jolt)

### `libs.test-http/test-curl-bodiless-post`
`test/kmet/libs/test_http.clj:469`, transport `curl` (one of the two
`deftest-transports` runs):
`network error: Proxy request failed: curl: (56) Recv failure: Connection
was reset {:type :transport-error, :exit 56}`. Jolt: failed in the full run
and in 4 of 20 standalone reruns on v0.8.15 (2026-09-30). bb: green in the
full run and 2/2 standalone reruns.

### `libs.test-http/test-curl-direct-proxy-map`
Same curl 56 in the Jolt full run; standalone on v0.8.15 it failed 2 of 20
runs (one surfaced as an uncaught exception, not an assertion error). bb:
green standalone.

Both are the same family as `libs.test-http/test-curl-redirect-slow-second-hop`
(curl 97, `jolt-port.md` §Tests), but that one stayed green standalone on
v0.8.15 (3/3). These two persist standalone, so the follow-up is due: check
the test SOCKS proxy's handling of the curl transport on Windows.

## Resolved for context

The six tool-call-pairing assertions in `app.test-loop`,
`app.ui.test-chat-history` and `modes.test-interactive` were not Windows
failures at all: they only fired when terminal detection enabled hyperlinks
(`WT_SESSION`), because the shared `tu/strip-ansi` helper stripped CSI but
not the OSC 8 sequences `tool-renderers/link-path` emits. Fixed in
`ce402e5` (delegates to `kmet.tui.utils/strip-ansi-codes`).
