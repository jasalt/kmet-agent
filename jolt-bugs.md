# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-27 against the tagged `v0.8.23` (released 2026-10-17)
and the installed `v0.8.23-154-g8ba11735` dev build on `main`, which
`:jolt/min-version` now names (`0.8.23`). The tag carries the fixes for #1379,
#1380 and #1395 ([PR #1382](https://github.com/jolt-lang/jolt/pull/1382),
[PR #1396](https://github.com/jolt-lang/jolt/pull/1396)), so those entries are
dropped and the `sort-by str` path workaround that outlived them went with the
floor. Also checked: `jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged;
`HEAD` is that same revision), the version pinned by the project.

Filing: [`#1433`](https://github.com/jolt-lang/jolt/issues/1433) (shim enums
lack `valueOf`/`values`) was filed 2026-10-24 against the installed
`v0.8.23-47-g75c8b610` dev build, where it reproduced, and closed on `main`
2026-10-26 by [PR #1435](https://github.com/jolt-lang/jolt/pull/1435) (merge
`4fd288a2`, follow-up
[#1437](https://github.com/jolt-lang/jolt/pull/1437)), verified on
`v0.8.23-154-g8ba11735`. No tag carries it yet, so the entry and its workaround
stay. `#1031` is unchanged: still open, as is
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093).

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open (last upstream update 2026-10-09), as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself (no
update since 2026-09-16). It affects extensions that choose the SCI loader
backend on Jolt and copy or implement host protocols; shipped extensions
declaring `:jolt` use Jolt's native loader and are not blocked, which is why
`test-shipped-extensions-load-from-src` runs on both hosts. `jolt/deps.edn` and
`modules/app/deps.edn` pin yogthos/sci at `1295142` (the #1093 branch) for the
`:sci` fallback. Keep the pin until the change is released by Jolt.

### [jolt#1433](https://github.com/jolt-lang/jolt/issues/1433) — `valueOf`/`values` missing from Jolt's shim-provided enums

Static *fields* on enum classes resolve, but the JVM-synthesized static
methods `valueOf`/`values` do not, for the enums Jolt's dependency shims
provide (`java.util.concurrent`, `java.nio.file`, `java.text`). Enums from
the fuller `java.time` implementation work, and static methods generally
work, so this is the shim layer rather than interop as a whole.

```clojure
;; works: static fields, static methods, java.time enums
java.util.concurrent.TimeUnit/MILLISECONDS          ; => MILLISECONDS
java.nio.file.StandardOpenOption/WRITE              ; => #object[:object]
java.text.Normalizer$Form/NFKC                      ; => NFKC
(java.time.ZoneOffset/ofTotalSeconds 0)             ; => Z
(java.time.DayOfWeek/valueOf "MONDAY")              ; => MONDAY
(java.time.DayOfWeek/values)                        ; => [MONDAY ... SUNDAY]
(java.lang.Math/abs -1)                             ; => 1

;; fails: the same shape on shim-provided enums
(java.util.concurrent.TimeUnit/valueOf "MILLISECONDS")
;; IllegalArgumentException: No matching field or method: java.util.concurrent.TimeUnit/valueOf
(java.util.concurrent.TimeUnit/values)
(java.nio.file.StandardOpenOption/valueOf "WRITE")
(java.nio.file.StandardOpenOption/values)
(java.text.Normalizer$Form/valueOf "NFKC")
(java.text.Normalizer$Form/values)

;; and the interface itself is not provided at all:
(java.lang.Enum/valueOf java.time.DayOfWeek "MONDAY")
;; No dependency provides java.lang.Enum — a concrete implementation of the JDK
;; classes must be provided ... (RFC 0014)
```

Expected: either resolve them the way the JVM does, or fail with a message
that names the class as shim-provided (the RFC 0014 text above is a good
model) instead of reporting a missing member.

Impact: `kmet.libs.sse` polls a queue with a `TimeUnit` and
`kmet.libs.edit-diff` normalizes with a `Normalizer$Form`; writing those as
the portable static-method form (`(TimeUnit/valueOf "MILLISECONDS")`) loaded
fine on babashka and inside an extension's SCI but threw at *load* on Jolt —
`jolt start` died in `kmet.libs.edit_diff`. The reverse spelling is no better:
extension SCI resolves static methods but has no static-field access, so a
field read inside a function body throws there.

Workaround (kmet): read the field once into a top-level `def` initializer —
that code runs on the host and the sandbox receives the value by reference, so
one spelling serves both hosts — and `kmet.libs.test-self-contained` /
`libs-name-no-static-fields-in-sandbox-code` fails a static field anywhere a
sandbox would execute it (mutations checked: `defn` body, a lambda and a
`fn` nested in a `def` initializer all fail by name; a field as the initializer
itself passes). Closed 2026-10-26 by [PR #1435](https://github.com/jolt-lang/jolt/pull/1435)
(merge `4fd288a2`; follow-up
[#1437](https://github.com/jolt-lang/jolt/pull/1437)), verified on the
installed `v0.8.23-154-g8ba11735` dev build: `TimeUnit`, `StandardOpenOption`
and `Normalizer$Form` answer `valueOf`/`values`, `(.isEnum TimeUnit)` is true,
and `Enum/valueOf`/`getEnumConstants` exist. The tag the floor names,
`v0.8.23`, predates the fix, so the entry and the workaround stay live until a
tag carries it.
