# jolt-bugs — reported upstream issues

Open tickets only: a ticket is dropped once its fix reaches a tagged release.
Status checked on 2026-10-29 against the tagged `v0.8.23` (released 2026-10-17)
and the installed `v0.8.23-199-g0ac561e1` dev build on `main`, which
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

Filing: [`#1453`](https://github.com/jolt-lang/jolt/issues/1453) and
[`#1454`](https://github.com/jolt-lang/jolt/issues/1454) were filed 2026-10-29
against the installed `v0.8.23-199-g0ac561e1` dev build, where both
reproduced. They are the writer and reader halves of one defect — non-BMP
characters cannot cross Jolt's JSON boundary intact in either direction — and
neither is fixed or tagged yet.

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

### [jolt#1453](https://github.com/jolt-lang/jolt/issues/1453) — `data.json` writes non-BMP code points as 5–6 digit `\u` escapes

Jolt strings are code-point based and a Jolt `char` holds a full scalar, so
`clojure.data.json`'s writer — which escapes non-ASCII through a fixed-width
`\u%04x` of the character's integer value — emits 5 or 6 hex digits for
anything above U+FFFF instead of the surrogate pair RFC 8259 requires. BMP
text is unaffected (`é` → `\u00e9`); an astral code point is not (U+1F44D →
`\u1f44d`). A JSON `\u` escape carries exactly four hex digits, so a peer's
parser reads the first four (`1f44`) as the character and the leftover fifth
digit (`d`) as literal text.

Impact, observed end to end: a chat message containing U+1F44D U+1F3FB left
kmet as `\u1f44d\u1f3fb` in a request body and the transcript persisted
`U+1F44 "d" U+1F3F "b"` — silent, not a visible error.

Workaround (kmet): `kmet.libs.json/base-write-opts` passes
`:escape-unicode false`, so payloads go out as raw UTF-8 — valid JSON on every
host, and the escape branch is never entered. `astral-round-trip` in
`kmet.libs.test-json` pins it and fails on Jolt without the option. The option
and the test stay past the fix (raw UTF-8 is also the smaller encoding); the
entry retires when a tag carries a fixed writer.

### [jolt#1454](https://github.com/jolt-lang/jolt/issues/1454) — `data.json` rejects valid surrogate-pair escapes

The read half of the same defect: the reader decodes every `\u` escape through
`char`, which correctly refuses surrogate code units (`(char 0xD83D)` throws
`Value out of range for char: 55357`), and never combines a pair — so valid
JSON that escapes a non-BMP character as `\ud83d\udc4d` cannot be parsed at
all. It also accepts the malformed 5-digit form and splits it into two
characters, which is what turned #1453's output into corrupted data instead of
a loud parse error.

Impact (unmitigated): any peer that escapes astral characters in its JSON —
Python's `json.dumps` default, Jackson, PHP, .NET — makes the parse throw on
Jolt, while payloads carrying raw UTF-8 work, so the failure depends on the
peer's serializer rather than on the data. Provider responses, MCP and tool
results all cross this seam.

Not worked around: kmet keeps `parse-string` as the data.json alias. The
narrow fix, if taken before upstream lands, is a `:jolt`-only pre-pass in
`kmet.libs.json` that recombines surrogate escape pairs while tracking escaped
backslashes; it duplicates parsing logic at the seam and goes away with the
reader fix.

