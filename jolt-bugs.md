# jolt-bugs — reported upstream issues

A ticket remains live here until its fix reaches a tagged release. Status
checked on 2026-10-11 against Jolt `v0.8.21` (`5b46858a`, the latest tag) and
`jolt-lang/http-client` `v0.1.2` (`00455b8`), the versions now pinned by the
project.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open. It affects extensions that choose the SCI loader backend on Jolt
and copy or implement host protocols; shipped extensions using Jolt's native
loader are not blocked. `jolt/deps.edn` pins the SCI revision from
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) for the `:sci`
fallback. Keep the pin until the change is released by Jolt.

## Resolved in the pinned releases

These issues no longer need kmet-side workarounds:

- [jolt#1281](https://github.com/jolt-lang/jolt/issues/1281), Windows 8.3
  short-name canonicalization, and
  [jolt#1283](https://github.com/jolt-lang/jolt/issues/1283), closing a
  Windows process pipe with a read in flight, are fixed in Jolt `v0.8.20`
  ([PR #1287](https://github.com/jolt-lang/jolt/pull/1287)).
- [jolt#1284](https://github.com/jolt-lang/jolt/issues/1284), concurrent
  protocol dispatch in `--opt` builds, is fixed in Jolt `v0.8.20`
  ([PR #1286](https://github.com/jolt-lang/jolt/pull/1286)).
- [jolt#1282](https://github.com/jolt-lang/jolt/issues/1282), HTTP request
  timeout exception parity, is fixed in `jolt-lang/http-client` `v0.1.2`
  ([PR #36](https://github.com/jolt-lang/http-client/pull/36)). Its existing
  v0.0.14 behavior also keeps request deadlines from
  cutting off an SSE body after response headers arrive.
- [jolt#1292](https://github.com/jolt-lang/jolt/issues/1292) and
  [jolt#1294](https://github.com/jolt-lang/jolt/issues/1294), the GC stall
  report and the collect-safe `:blocking` cost behind the stall, are fixed
  in Jolt `v0.8.21`
  ([PR #1293](https://github.com/jolt-lang/jolt/pull/1293),
  [PR #1294](https://github.com/jolt-lang/jolt/pull/1294)): the stall report
  needs `JOLT_GC_STALL`, the runtime's other warnings `JOLT_WARNINGS`, and
  `jolt.ffi/on-gc-stall` remains the seam for a host with its own log. No
  kmet-side workaround to remove.
- [jolt#1299](https://github.com/jolt-lang/jolt/issues/1299), `instance?`
  against the modeled reference/deferred types, is fixed in Jolt `v0.8.21`
  ([PR #1302](https://github.com/jolt-lang/jolt/pull/1302)): the hot checks
  measure 0.06–0.22 µs against 1.1–1.9 before. The kmet-side classify-once
  shape in `kmet.libs.reakt` stays (it halves the classification work on
  both hosts); only its comments citing the old jolt cost went.
- [jolt#1303](https://github.com/jolt-lang/jolt/issues/1303), the timed
  `LinkedBlockingQueue` poll, is fixed in Jolt `v0.8.21`
  ([PR #1305](https://github.com/jolt-lang/jolt/pull/1305)); the same batch
  put runtime deadlines on the monotonic clock. `kmet.libs.sse` dropped its
  untimed-poll-first accommodation and takes the plain timed poll again.

Regression coverage includes runtime-cwd canonicalization
(`kmet.app.test-extensions/test-exec-runs-in-the-runtime-cwd`), HTTP request
and SSE idle timeouts (`kmet.ai.test-llm/test-llm-request-timeout-follows-idle`,
`test-llm-body-stall-idle-timeout-completes`, and the idle reader's
`kmet.libs.test-sse/test-char-idle-reader-times-out`), and the detached-process
pipe drain (`kmet.app.test-tools/test-tool-bash-background-pipe-closed`).
