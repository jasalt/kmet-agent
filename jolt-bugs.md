# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-15 against Jolt `v0.8.22` (`1db4cc98`, the latest
tag). It carries every fix the v0.8.21 floor had left on `main` — #1313
(PR #1314), #1316 (PR #1320), #1327 (PR #1330) and #1364 (PR #1366) — so
those entries are dropped per the rule above, and the kmet workarounds gated
on #1316/#1364 were already removed 2026-10-15. Also checked:
`jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged; `HEAD` is that same
revision), the versions now pinned by the project. `:jolt/min-version` names
`0.8.22`, so a source build matches the tag.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open, as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself. It
affects extensions that choose the SCI loader backend on Jolt and copy or
implement host protocols; shipped extensions using Jolt's native loader are
not blocked. `jolt/deps.edn` pins that SCI revision for the `:sci` fallback.
Keep the pin until the change is released by Jolt.
