# Porting kmet to Jolt — open work

The port is staged and largely landed: kmet runs on Jolt — TUI (native
terminal backend: termios on Unix, kernel32 on Windows), providers (HTTP
via babashka.http-client over the jolt-lang shims, including the Windows
transport from http-client v0.0.15), packaging (`jolt dist`), and extensions
(the native loader, with SCI as the declared fallback). This file tracks
**only what is still open**; finished work lives in the code, and upstream
issues filed or tracked live in `jolt-bugs.md` and are not repeated here.

Status checked against `jolt v0.8.15` (2026-09-30).

## Tests

- **Flaky (Jolt-only, unpinned)**:
  `tui.test-compute/compute-change-invalidates-subscriber-and-schedules-frame`
  and `tui.test-hiccup/dep-change-schedules-a-frame-through-the-hook` each
  failed once in ~18 full runs ("rendering itself does not poke the hook",
  fired = 2); never standalone; mechanism unpinned.
  `app.test-loop/test-loop-cancel-delivers-promise` and
  `test-loop-cancel-records-aborted-attempt` miss their settle windows in
  some full `jolt test` runs (2 of 4) and in one loaded `bb test-changed`
  run; standalone they pass on both hosts.
- **Flaky under load (Jolt)**:
  `app.test-tools/test-tool-bash-background-pipe-closed` (12.5 s against its
  8 s window) and `app.test-run-code/test-run-code-await-all-honors-timeout`
  (13.7 s against its 1 s window) each missed once in a loaded `jolt
  test-ext`; both pass standalone and on bb.
- **Flaky under load**: `libs.test-http/test-curl-redirect-slow-second-hop`
  — curl 97 "Connection reset by peer" through the test SOCKS proxy while
  the suite is loaded; green standalone (3/3 on v0.8.15).
- **Flaky standalone (Jolt on Windows)**:
  `libs.test-http/test-curl-bodiless-post` (4 of 20 standalone reruns on
  v0.8.15) and `libs.test-http/test-curl-direct-proxy-map` (2 of 20) fail
  with curl 56 "Recv failure: Connection was reset" through the test SOCKS
  proxy; the Windows-host full-run misses are in `windows.md` §Flaky. The
  test SOCKS proxy's curl transport on Windows is the follow-up.
- **`modes.test-overlay-input-smoke`** is `^:bb-only` (its driver spawns
  `bb start`, so on Jolt it would exercise bb's TUI); a Jolt-host pty variant
  is the follow-up.
