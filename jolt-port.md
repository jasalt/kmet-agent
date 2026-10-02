# Porting kmet to Jolt — open work

The port is staged and largely landed: kmet runs on Jolt — TUI (native
terminal backend: termios on Unix, kernel32 on Windows), providers (HTTP
via babashka.http-client over the jolt-lang shims, including the Windows
transport from http-client v0.0.15), packaging (`jolt dist`), and extensions
(the native loader, with SCI as the declared fallback). This file tracks
**only what is still open**; finished work lives in the code, and upstream
issues filed or tracked live in `jolt-bugs.md` and are not repeated here.

Status checked against `jolt v0.8.15-77-ga1f6b669` (2026-10-02).

## Dependency pins

The git pins track their upstream releases (checked 2026-10-02):

- `jolt-lang/jolt-crypto` — `v0.0.10` (`feb25f70`): the `(bytes, off, len)`
  overload fixes and the quadratic `MessageDigest.update` fix (PR #13)
  plus the macOS stub/libcrypto `:link-libs` change (PR #14). Keyed
  `jolt-lang/jolt-crypto` to match the lib name upstream http-client
  depends on, so this root pin supersedes the transitive v0.0.9.
- `io.github.jolt-lang/http-client` — `v0.0.17` (`77d7e310`): interrupted
  connects are retried and TLS streams/contexts released once a connection
  fails (PRs #30, #31). Its stale `:jolt/provides` claim on
  `java.util.concurrent.CompletableFuture` is filed upstream
  ([http-client#32](https://github.com/jolt-lang/http-client/issues/32));
  jolt-bugs.md carries the pin's move condition.

## Tests

- **Flaky (Jolt-only, unpinned)**:
  `tui.test-compute/compute-change-invalidates-subscriber-and-schedules-frame`
  and `tui.test-hiccup/dep-change-schedules-a-frame-through-the-hook` each
  failed once in ~18 full runs ("rendering itself does not poke the hook",
  fired = 2); never standalone; mechanism unpinned.
  `app.test-loop/test-loop-cancel-delivers-promise` and
  `test-loop-cancel-records-aborted-attempt` miss their settle windows in
  some full `jolt test` runs (2 of 4) and in one loaded `bb test-changed`
  run; standalone they pass on both hosts. Not reproduced on
  `v0.8.15-46`: 10/10 filtered reruns green on Jolt; the mechanism is still
  unpinned.
- **Flaky under load (Jolt)**:
  `app.test-tools/test-tool-bash-background-pipe-closed` (12.5 s against its
  8 s window) and `app.test-run-code/test-run-code-await-all-honors-timeout`
  (13.7 s against its 1 s window) each missed once in a loaded `jolt
  test-ext`; both pass standalone and on bb. Not retested on `v0.8.15-46`.
- **Flaky under load**: `libs.test-http/test-curl-redirect-slow-second-hop`
  — curl 97 "Connection reset by peer" through the test SOCKS proxy while
  the suite is loaded. Not reproduced on `v0.8.15-46`: 4/4 standalone and
  6/6 under a full fast-suite load. The test proxy now half-closes each
  direction with a real `Socket.shutdownOutput` (jolt#1208), which removes
  the reset path; keep an eye on it.
