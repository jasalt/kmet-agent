# jolt-bugs — live upstream workarounds

Only live kmet-side workarounds for Jolt upstream issues are listed here,
with exactly what to remove or update. A ticket is done only when its fix
reaches a tagged release: closed upstream but unreleased still counts as
live, because the workaround protects the declared floor (the latest tagged
release). Status checked against GitHub and the re-fetched `origin/main` on
2026-10-03, with the released `jolt v0.8.16` build installed (latest tag:
v0.8.16).

## Live workarounds

Each entry below records the kmet-side workaround (if any) and exactly what
to remove or update, whether the upstream ticket is still open or its fix is
awaiting a release.

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (labeled `deferred`; last upstream activity 2026-09-18). It affects
extensions that choose the SCI loader backend on Jolt and
copy/implement host protocols. Shipped extensions using Jolt's native loader
are not blocked. The fix is proposed in
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093), still open at
head `1295142f`. `jolt/deps.edn` pins that SCI revision for the `:sci`
fallback; keep the pin until the change is released by Jolt.

### [jolt-lang/http-client#32](https://github.com/jolt-lang/http-client/issues/32) — stale `CompletableFuture` claim

**Closed upstream** by http-client PR #34 (`d99af98`, 2026-10-02): the
stale claim is gone and the library's `:jolt/min-version` is 0.8.16.
Jolt v0.8.16 was tagged 2026-10-03 and carries
`java.util.concurrent.CompletableFuture` (jolt-lang/jolt@9dad0359), so
both `:jolt/min-version` declarations are now 0.8.16 and the revision's
floor no longer blocks. No http-client release carries PR #34 yet (latest
tag: v0.0.17, `77d7e310`), so the pin is the unreleased merge itself —
`d99af98` — and the dropped-claim warning is gone with it.

The floor bump was load-bearing, because the claim was what autoloaded the
shim for a bare `CompletableFuture` reference — verified on v0.8.15 with
the class removed: a bare `CompletableFuture/completedFuture` fails with
"No dependency provides java.util.concurrent.CompletableFuture", while
babashka.http-client's sync/`:async`/websocket paths still work (they
autoload the shim through `HttpClient`/`HttpRequest`). When the first
release carrying PR #34 (v0.0.18 or later) is tagged, move the
`io.github.jolt-lang/http-client` pin to the tag and drop the note in
`deps.edn` and `jolt-port.md`.
