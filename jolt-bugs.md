# jolt-bugs — live upstream workarounds

Only live kmet-side workarounds for Jolt upstream issues are listed here,
with exactly what to remove or update. A ticket is done only when its fix
reaches a tagged release: closed upstream but unreleased still counts as
live, because the workaround protects the declared floor (the latest tagged
release). Status checked against GitHub and the re-fetched `origin/main` on
2026-10-02, with the local `jolt v0.8.15-101-g748ddc29` build installed
(latest tag: v0.8.15).

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

### [jolt#1219](https://github.com/jolt-lang/jolt/issues/1219) — AOT recovery bakes shadowed clojure.core names into the cache

**Fixed on main** (`0bbc15a0`, PR #1220, 2026-10-02): recovery now takes
back the defs a cached load made before recompiling. **Not in v0.8.15** —
its `recover!` path is the affected one — so the workaround stays until the
first tagged release that carries the fix. Re-ran the reproducer on the
fixed build (`v0.8.15-101-g748ddc29`): the clean compile gives `:f 1` and
every truncation tail reports "not reproduced" (exit 1).

When a cached AOT artifact loads incompletely (or a recorded assumption
goes stale), loader.ss's `recover!` recompiles the namespace from source in
the same process, after the old artifact has interned its vars. The analyzer
resolves unqualified symbols in the current ns first, so a `defn` that
shadows a `clojure.core` name captures earlier forms too — and the poisoned
artifact is written back, breaking every later process until its cache entry
is deleted.

kmet's `kmet.libs.http` exposed it: its public `get` (the HTTP GET helper)
captured the internal `(get env n)` in `env-first` and `(get @client-cache
key)` in `client-for` on a recovery compile, producing the persistent runtime
error `class java.lang.String cannot be cast to class
clojure.lang.Associative` (the compiled `.scm` called `kmet.libs.http/get`
from `client-for`). Worked around kmet-side by qualifying both sites
`clojure.core/get` plus a warning comment at `defn get`;
`scripts/repro_jolt_aot_shadow.bb` reproduces the poisoning with a two-def
fixture.

Once a tagged release carries the fix, remove the workaround: revert both
sites to plain `get`, drop the comment at `defn get`, and delete
`scripts/repro_jolt_aot_shadow.bb`. Running that script is the acceptance
check — on the fixed build it must stop reproducing (exit 1, "not
reproduced") before the qualifications come out.

### [jolt-lang/http-client#32](https://github.com/jolt-lang/http-client/issues/32) — stale `CompletableFuture` claim

**Closed upstream** by http-client PR #34 (`d99af98`, 2026-10-02): the
stale claim is gone and the library's `:jolt/min-version` is now 0.8.16,
the first release that will carry `java.util.concurrent.CompletableFuture`
(jolt-lang/jolt@9dad0359 is on main; v0.8.15 does not have it). No v0.8.16
tag exists yet, and moving kmet's pin now would make jolt v0.8.15 refuse
the project, so the pin stays at `v0.0.17` (`77d7e310`).

The floor bump is load-bearing, because the claim was what autoloaded the
shim for a bare `CompletableFuture` reference — verified on v0.8.15 with
the class removed: a bare `CompletableFuture/completedFuture` fails with
"No dependency provides java.util.concurrent.CompletableFuture", while
babashka.http-client's sync/`:async`/websocket paths still work (they
autoload the shim through `HttpClient`/`HttpRequest`). When v0.8.16 is
tagged, move the `io.github.jolt-lang/http-client` pin to the released fix
(v0.0.18 or later), bump both `:jolt/min-version` declarations with the
floor, and drop the note in `deps.edn` and `jolt-port.md`.
