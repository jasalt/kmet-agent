# jolt-bugs — open upstream tickets

Only currently open Jolt upstream issues relevant to kmet are listed here.
Closed tickets and completed work are intentionally omitted.
Status checked against GitHub and the re-fetched `origin/main` on
2026-10-01, with the local `jolt v0.8.15-46-g005d134b` build installed.

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
