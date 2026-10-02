# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Closed tickets and completed work are intentionally omitted.
Status checked against GitHub and the re-fetched `origin/main` on
2026-10-02, with the local `jolt v0.8.15-77-ga1f6b669` build installed.

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

### [jolt-lang/http-client#32](https://github.com/jolt-lang/http-client/issues/32) — stale `CompletableFuture` claim

Filed 2026-10-02. jolt main provides
`java.util.concurrent.CompletableFuture` (jolt-lang/jolt@9dad0359; not in
v0.8.15), so the shim's `:jolt/provides` claim on it is dropped at startup
with "upgrade io.github.jolt-lang/http-client". The fix is the java.util.zip
shape of http-client PR #25: drop the class from `jolt.http.jdk`'s
`:jolt/provides` and raise the library's `:jolt/min-version` to the first
Jolt release after v0.8.15 that carries the class; the floor bump is
load-bearing, because the claim is what autoloads the shim for a bare
`CompletableFuture` reference.

No kmet-side workaround. Moving the pin now regresses released Jolt —
verified on v0.8.15 with the class removed: a bare
`CompletableFuture/completedFuture` fails with "No dependency provides
java.util.concurrent.CompletableFuture", while babashka.http-client's
sync/`:async`/websocket paths still work (they autoload the shim through
`HttpClient`/`HttpRequest`). When the fixed revision exists, move the
`io.github.jolt-lang/http-client` pin to it and drop the note in `deps.edn`
and `jolt-port.md`.
