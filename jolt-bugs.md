# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-17 against Jolt `v0.8.22-49-ga108b749` (dev build on `main`
ahead of the tagged `v0.8.22`). It carries every fix the v0.8.21 floor had left on `main` — #1313
(PR #1314), #1316 (PR #1320), #1327 (PR #1330) and #1364 (PR #1366) — so
those entries are dropped per the rule above, and the kmet workarounds gated
on #1316/#1364 were already removed 2026-10-15. Also checked:
`jolt-lang/http-client` `v0.1.2` (`00455b8`, unchanged; `HEAD` is that same
revision), the versions now pinned by the project. `:jolt/min-version` names
`0.8.22`, so a source build matches the tag. Two further defects (`#1379`,
`#1380`) were found porting the RPC front end and filed
2026-10-15 against this revision.

Re-checked the same day: both were closed on `main` by
[PR #1382](https://github.com/jolt-lang/jolt/pull/1382) (merge `aaf2dfe1`,
listed under CHANGELOG `[Unreleased]`), verified on the installed
`v0.8.22-15-gaaf2dfe1` dev build, and the kmet workarounds were removed
ahead of the tag. `#1395` was closed on `main` on 2026-10-17 by
[PR #1396](https://github.com/jolt-lang/jolt/pull/1396) (merge `a108b749`),
verified on the installed `v0.8.22-49-ga108b749` dev build (`compare`/`sort`
on `Path` values now works). The entries stay live until a tag carries the
fixes; until then a source build wants a Jolt from `main`. `#1031` is
unchanged: still open, as is
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093).

Filing: [`#1433`](https://github.com/jolt-lang/jolt/issues/1433) (shim enums
lack `valueOf`/`values`) was filed 2026-10-24 against the installed
`v0.8.23-47-g75c8b610` dev build, where it still reproduces; no fix on `main`
yet.

## Live issues

### [jolt#1031](https://github.com/jolt-lang/jolt/issues/1031) — SCI host-protocol support on Jolt

Still open, as is the upstream
[babashka/sci#1093](https://github.com/babashka/sci/pull/1093) itself. It
affects extensions that choose the SCI loader backend on Jolt and copy or
implement host protocols; shipped extensions using Jolt's native loader are
not blocked. `jolt/deps.edn` pins that SCI revision for the `:sci` fallback.
Keep the pin until the change is released by Jolt.

### [jolt#1379](https://github.com/jolt-lang/jolt/issues/1379) — `clojure.java.io/writer` over an OutputStream loses data

`(io/writer <OutputStream>)` accepts writes, but `.flush` and `.close` never
reach the stream underneath:
the bytes sit in the stream's own port buffer, so a file stream stays empty
and a child process never sees its stdin. The `jolt-io-writer` overlay's
`out-stream?` arm (`host/chez/java/io-streams.ss`) passes `#f` as
`make-charset-writer`'s downstream, so the char-writer's flush/close skip the
stream; the `user-out-stream?` arm passes the stream. Closed 2026-10-15 by
[PR #1382](https://github.com/jolt-lang/jolt/pull/1382) (merge `aaf2dfe1`,
on `main` after the tagged `v0.8.22`). The kmet workaround —
`kmet.libs.process/write-stdin!` writing UTF-8 bytes with a direct flush,
with `kmet.agent.modes.rpc-client` holding the raw `:in` — was removed 2026-10-15
ahead of the tag: the client holds `(io/writer (:in p) :encoding "UTF-8")`
and flushes it per record. The entry stays live until a tag carries the fix;
on the `v0.8.22` floor that wrapper would buffer.

### [jolt#1380](https://github.com/jolt-lang/jolt/issues/1380) — `io/reader` cannot coerce `*in*`

Jolt's `*in*` is a `clojure.core/IReader` reify, not a `java.io.Reader`, and
the `jolt-io-reader` overlay has no arm for it, so the ordinary JVM way to
read stdin — `(with-open [r (io/reader *in*)] (line-seq r))` — throws
`IllegalArgumentException: Cannot open <#object[clojure.lang.IObj$reify__0]>
as a Reader`. (`clojure.core/read-line` works; only the coercion does not.)
Workaround: `kmet.agent.modes.rpc/serve-input` read the default input with
`clojure.core/read-line` and wrapped only an explicit `:in` override.
Closed 2026-10-15 by [PR #1382](https://github.com/jolt-lang/jolt/pull/1382)
(merge `aaf2dfe1`); the workaround was removed ahead of the tag —
`serve-input` wraps `(or in *in*)` with `io/reader` on both hosts. The entry
stays live until a tag carries the fix.

### [jolt#1395](https://github.com/jolt-lang/jolt/issues/1395) — `clojure.core/compare` cannot compare `java.nio.file.Path`

Jolt's `clojure.core/compare` raises a `ClassCastException` when comparing
two `java.nio.file.Path` values (e.g. those returned by `babashka.fs/list-dir`),
claiming that one path "cannot be compared to" the other. Although `Path`
implements `Comparable` and its `.compareTo` method works directly, `compare`
does not dispatch to it, so `clojure.core/sort` fails on paths. Babashka/JVM
sort the same paths normally.

Repro:

```clojure
(require '[babashka.fs :as fs])
(def paths (fs/list-dir "modules"))
(compare (first paths) (second paths))  ; throws in Jolt
(sort paths)                            ; also fails
```

Closed 2026-10-17 by [PR #1396](https://github.com/jolt-lang/jolt/pull/1396)
(merge `a108b749`, on `main` after the tagged `v0.8.22`), verified on the
installed `v0.8.22-49-ga108b749` dev build: `compare`/`sort` on `Path`
values now works. The kmet workaround — `(sort-by str ...)` on path
collections — stays in place because the `v0.8.22` floor still lacks the fix;
it is also harmless and keeps ordering deterministic. The entry stays live
until a tag carries the fix.

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
itself passes). Filed 2026-10-24, still open, no fix on `main`; reproduced on
the installed `v0.8.23-47-g75c8b610` dev build (reflection confirms the shim
enum classes declare neither `values()` nor `valueOf(String)`, so nothing
synthesizes them). The entry stays live until a tag carries the fix, and it
drops then if the workaround stays harmless.
