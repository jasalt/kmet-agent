# Windows host — outstanding test failures

Recorded 2026-09-29 on Windows 11 26200 in Windows Terminal (`WT_SESSION`
set), Jolt `v0.8.14-29-gc7c6b33e`; re-verified 2026-09-30 on `jolt v0.8.15`
(`924d80e9`), after the Windows fixes below. The fast suites now produce:

- `bb test` — 2742 tests / 17800 assertions, 0 failures + 0 errors
- `jolt test` — 2722 tests / 17700 assertions, 0 failures + 1 error

The one error is upstream and tracked in `jolt-bugs.md`:

## Jolt-only: Windows `file:` resource paths (tracked upstream)

### `loader.test-jolt-loader/adapter-host-view-is-the-extension-contract`
`test/kmet/loader/test_jolt_loader.clj:111` —
`java.io.FileNotFoundException: \C:\src\my\kmet\src\kmet\loader\loader.md
(Invalid argument)`. **Not** the runtime's URI-to-path constructors (fixed
in v0.8.15): it is `jolt.loader/file-url-path` (`stdlib/jolt/loader.clj:350`)
stripping `file:` with `(subs s 5)`, so `file:/C:/…` becomes the invalid
`\C:\…` on Windows. Tracked as
[jolt#1203](https://github.com/jolt-lang/jolt/issues/1203). `bb test` passes;
nothing to fix in kmet.

## Fixed 2026-09-30

Each was rerun standalone on both hosts; all green afterwards:

- **Path separators** — `tasks.test-slop/source-discovery-…`,
  `tasks.build-test/stage-bundled-extensions-…` and
  `tasks.format-test/test-source-paths` compared `fs/file`/`fs/relativize`
  output raw; they now compare through `kmet.test-utils/slash` (`e43d97b`
  and its follow-up).
- **POSIX permissions** — `install-artifact!`, `assemble-one!` and the jolt
  `assemble!` skipped the chmod only for a Windows TARGET, so a Windows host
  cross-packaging a linux artifact died with `UnsupportedOperationException`;
  the guard now asks the host (`fs/windows?`) too (`e43d97b`).
- **CRLF capture** — `tasks.test-help/with-help-test` compared bb's CRLF
  `with-out-str` output raw; now through the new `out-str-lf` (`e43d97b`).
- **curl 56 through the test SOCKS proxy** —
  `libs.test-http/test-curl-bodiless-post` and `test-curl-direct-proxy-map`
  failed standalone on Jolt/Windows (4/20 and 2/20). The proxy's pump
  half-closes with `Socket.shutdownOutput`, which Jolt lacks
  ([jolt#1208](https://github.com/jolt-lang/jolt/issues/1208)); the caught
  no-op plus Windows' close-over-pending-recv produced the RST curl reported
  as `(56)`. The proxy now briefly joins the client→target pump before
  closing; 0/60 jolt and 0/20 bb reruns.

## Resolved for context

The six tool-call-pairing assertions in `app.test-loop`,
`app.ui.test-chat-history` and `modes.test-interactive` were not Windows
failures at all: they only fired when terminal detection enabled hyperlinks
(`WT_SESSION`), because the shared `tu/strip-ansi` helper stripped CSI but
not the OSC 8 sequences `tool-renderers/link-path` emits. Fixed in
`ce402e5` (delegates to `kmet.tui.utils/strip-ansi-codes`).
