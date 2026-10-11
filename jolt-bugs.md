# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-11 against Jolt `v0.8.21` (`5b46858a`, the latest
tag) and `jolt-lang/http-client` `v0.1.2` (`00455b8`), the versions now pinned
by the project.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open. It affects extensions that choose the SCI loader backend on Jolt
and copy or implement host protocols; shipped extensions using Jolt's native
loader are not blocked. `jolt/deps.edn` pins the SCI revision from
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) for the `:sci`
fallback. Keep the pin until the change is released by Jolt.
