# mcp-adapter refactor plan (before the 2026-07-28 protocol revision)

Status: draft. Order is deliberate: **Phase 0 (names consolidation + name
assignment fix) → Phase 1 (extract `kmet.libs.mcp`) → Phase 2 (auth) →
Phase 3 (optional hygiene) → Phase 4 (2026-07-28 protocol work)** — phases 2+
are tracked here but not started. The protocol revision lands last so the
client is extracted and auth settled first.

Scope: `extensions/mcp-adapter/` and the shared `kmet.libs` layer. Phase 0 and
Phase 1 change no protocol behavior; they move code so the stateless revision
lands in the protocol layer instead of across five files.

## Why now

MCP `2026-07-28` removes the `initialize` handshake and `Mcp-Session-Id`,
requires per-request `_meta` (version/capabilities/clientInfo), adds
`server/discover`, `Mcp-Method`/`Mcp-Name` headers, `resultType`, and
`subscriptions/listen`. Today's `client.clj` mixes transports, JSON-RPC
correlation, and handshake/catalog semantics; era detection and request
decoration would otherwise cut across all three. The extension also has two
copies of tool naming that registration, display and call resolution each
depend on — and they can disagree.

## How changes land

- **Move first, change second.** Phase 0 lands as 0.1 (extract/switch, behavior
  identical) then 0.2 (assignment fix, behavior change). A landing that both
  moves code and changes behavior cannot be bisected or reverted cleanly.
- **Phase 1 lands one transport at a time, risk ascending**: `protocol` (pure)
  → `transport.http` → `transport.sse` → `transport.stdio` (the only
  behavior-adjacent piece, because of the jsonrpc swap). Each step leaves the
  extension scripts green.
- **Baseline first**: capture the six `scripts/validate-*.bb` outputs (or add
  `scripts/validate-all.bb`, one command wrapping them) before 0.1. Every
  landing must reproduce or intentionally update that baseline.
- **Time-box the jsonrpc adoption** (1.0): if stdio-over-jsonrpc needs more
  than the `:id`-in-ex-data addition — per-request notification hooks, a second
  error taxonomy — stop and move the existing stdio transport as-is into
  `kmet.libs.mcp.transport.stdio`. `kmet.libs.jsonrpc` is shared with
  lsp-adapter; contorting it for MCP is worse than accepting the duplication
  temporarily.

## Decisions

| Area | Destination | Why |
|---|---|---|
| Transports (stdio, streamable HTTP, legacy SSE) + JSON-RPC client core + protocol constants/`_meta`/content | `kmet.libs.mcp.*` | Self-contained already (only `kmet.libs.*`, `babashka.process`, `io`, `core.async`); pi keeps them in a standalone `packages/mcp`; lib tests run under `bb test`/`jolt test` instead of manual scripts; the transport/era seam becomes an API boundary |
| SSE wire framing | `kmet.libs.sse` — **already exists, unchanged** | Generic `parse-sse-line` / `body->reader` / `make-idle-reader` / `stream-loop`; shared with `kmet.ai.api.sse`. The MCP transports use it, never re-implement it |
| MCP legacy SSE transport binding | `kmet.libs.mcp.transport.sse` | GET stream → `endpoint` event → POST URL + JSON-RPC correlation is MCP semantics, not generic SSE framing. **Frozen**: legacy SSE connections negotiate legacy protocol versions only, so Phase 4 must not touch it. Marked deprecated; removed when the HTTP+SSE off-ramp closes |
| Tool naming/selection + name *assignment* | `kmet.extensions.mcp-adapter.names` | Prefix modes, builtin collision, and include/exclude globs are adapter policy, not MCP protocol |
| Auth | extension now; `kmet.libs.mcp.auth` in Phase 2 | `kmet.libs.oauth` already holds discovery/PKCE/device/DCR/exchange; the rest is MCP-auth policy plus an issuer-keyed store |
| Config, metadata cache, catalog, direct tools, proxy, UI, prompts, output guard | extension | Host integration |

Constraints this plan must respect:

- Extensions may require only `kmet.extension`, `kmet.tui.*`, and `kmet.libs.*`;
  every new lib ns must be added to `libs-library-namespaces`
  (`src/kmet/app/extensions/context.cljc:222`) or the extension's require fails.
  Every lib is required once by the host at context build, so keep it lean.
- `kmet.libs.test-self-contained` today scans `src/kmet/libs/` only **one
  directory level** (its `lib-files` maps a directory's immediate children).
  Phase 1 introduces `src/kmet/libs/mcp/transport/*.clj`, so the guard must be
  fixed to recurse (a nested `transport/` dir is otherwise unchecked).
- `extension.edn` is `:loader [:jolt :sci]`: stick to plain maps/functions, no
  `defprotocol`/`deftype`, no new Java interop.
- The mcp-adapter has no test project; its suite is the six
  `scripts/validate-*.bb` files plus `scripts/e2e.bb`. Keep their public surface
  intact (a facade in `client.clj`) so they stay usable as integration tests.
- Confirm `jolt test` and the libs gates work **before** Phase 1 — don't
  discover a host gap after the extraction.

---

## Phase 0 — names consolidation + assignment fix (extension-local)

Status: **landed** (0.1, 0.2, 0.3) — `validate-all.bb` runs the seven-script
suite (names, client, config, oauth, panel, script, e2e), all green.

Goal: one source of truth for tool naming, and **display == registered name**
for every collision case. Today `core.clj` (registration) and `tool_proxy.clj`
(display/resolution) each own a copy, only `core.clj`'s has the builtin
collision fallback, and the registration `seen` set depends on the
direct-tools filter — so toggling `:direct-tools` can rename a tool, and with
`:tool-prefix :none` a colliding tool displays as `tool` but registers as
`server_tool`.

### 0.1. Move: `kmet.extensions.mcp-adapter.names` (`names.clj`), behavior identical

Pure primitives (moved, semantics preserved):

| Fn | Source |
|---|---|
| `builtin-tool-names` | `core.clj:35` (`read bash edit write grep find ls mcp`) |
| `sanitize-tool-name` | `core.clj:290`, `tool_proxy.clj:90` |
| `sanitize-server-name` | `tool_proxy.clj:112` |
| `resource-tool-name` | `tool_proxy.clj:96` |
| `prefix-for [server-name mode]` | `core.clj:296` (`:server`/`:short`/`:mcp`/`:none`) |
| `effective-mode [definition settings]` | pure replacement for `tool_proxy.clj:322` (no state deref) |
| `prefixed-name [server-name tool-name mode]` | composition without collision state (`tool_proxy.clj:329`) |
| `registration-name [server-name tool-name mode taken]` | today's collision-aware `prefixed-tool-name` (`core.clj:307`), kept through 0.1 and replaced by `assign-names` in 0.2 |
| `tool-name-candidates` | `tool_proxy.clj:117` (raw + legacy dash→underscore + every prefix spelling; kept for accepting legacy call spellings) |
| `tool-allowed?` | `tool_proxy.clj:162` (include/exclude globs; glob fns private) |

Caller switches in this landing (all behavior-identical):

- `core.clj`: `direct-tools-specs` — `names/tool-allowed?` (370),
  `names/registration-name` (371; today's collision-aware
  `prefixed-tool-name`), `names/resource-tool-name` (389, 399).
- `tool_proxy.clj`: `find-tool` (586), `search-text` (565), `describe-text`
  (613), `list-text` (664/669/671), `script-tool-records` (796),
  `resolve-search-keywords` (200) → `names/` (still `format-tool-name`
  semantics here; 0.2 replaces it with the assignment lookup).
- `prompts.clj`: prompt-command prefix (36, 39) → `names/sanitize-server-name` /
  `names/prefix-for`; keep `proxy/ensure-lazy-connected` and
  `proxy/truncate-at-word`.
- Delete the moved originals from `core.clj` and `tool_proxy.clj` (no
  re-export; scripts only use `proxy/call-mcp-tool`, verified).
- No changes in `panel.clj`, `setup.clj`, `tool_source.clj`, `auth.clj`,
  `config.clj`, `metadata.clj`.

### 0.2. Fix: deterministic name assignment (behavior change)

```clojure
(defn assign-names
  "Deterministic assigned name for every catalog entry.
   ENTRIES: [{:server s :kind :tool :id raw-tool-name}] plus resource entries
   {:server s :kind :resource :id uri} whose candidate base is
   read_<resource-tool-name>, over all configured, non-disabled servers.
   MODE-FOR: (fn [server] mode) — per-server :tool-prefix over settings.
   Returns {[server kind id] assigned-name}."
  [entries mode-for])
```

Rules:

1. Candidate = `prefixed-name` under the server's mode.
2. **Global collision detection, not first-claim.** Group entries by candidate.
   A candidate shared by more than one entry — or a bare candidate that is a
   builtin tool name — sends **every** entry in the group to the fallback.
   Order-independent: adding a colliding tool never renames an existing one and
   the assignment does not depend on cache/listing order. (Removing a colliding
   tool does not restore the plain name until the next refresh — accepted; it
   matches pi.)
3. Fallback = `sanitize(server) + "_" + sanitized`. If the fallback itself is
   shared by several entries (two server names that sanitize the same), append
   a short identity digest `(format "%08x" (hash [server kind id]))` to every
   entry in that group — pi's `createMcpToolName` suffix idea, shortened.
4. The assignment covers the **full enabled catalog** and is **independent of
   the direct-tools/include/exclude filters** — so changing which tools are
   registered never renames a tool.
5. Resource entries key on `[server :resource uri]` but their candidate base is
   the composed `read_<resource-tool-name>` — otherwise tool↔resource
   collisions slip through.

Wiring:

- `core.clj`: `sync-direct-tools!` builds the ordered entries once, calls
  `names/assign-names`, stores the result as `:tool-names` in the state map
  (init `{}` in `init-state`), and builds the direct specs from it (replacing
  the `seen` atom in `direct-tools-specs`).
- `tool_proxy.clj`: a single `display-name state server kind raw` helper
  (`(get (:tool-names state) [server kind raw])`, falling back to
  `names/prefixed-name` with `names/effective-mode`) replaces every
  `format-tool-name` call site. `find-tool` matches an assigned name before
  falling back to `tool-name-candidates`.

### 0.3. Tests and gates

- New `scripts/validate-names.bb` (pure, no servers):
  - display name == assigned name for a `:tool-prefix :none` collision and for
    a builtin collision (the divergence being fixed);
  - global collision rule: all members of a colliding group fall back, and
    assignment is order-independent (shuffled input, same result);
  - fallback equality for sanitized-server-name collisions gets the digest
    suffix;
  - assignment stable when the direct-tools filter changes;
  - resource candidate base `read_<...>` participates in collisions;
  - per-server `:tool-prefix` over settings; `:short` trims `-mcp`;
    `tool-name-candidates` still resolves legacy spellings;
  - glob include/exclude; `resource-tool-name` edge cases.
- New `scripts/validate-all.bb` wrapping the six existing scripts (baseline +
  per-landing check).
- Run the scripts that assert concrete names: `validate-panel.bb`,
  `validate-script.bb` (`fake_echo`), `e2e.bb` (`e2e_echo`,
  `e2e_read_project_files`, `e2e_read_issues_all`), `validate-config.bb`.
- `bb lint-changed`, `bb format-check-changed` (or `bb format-changed`).
- Baseline: run `validate-all.bb` before 0.1 and after each landing.

---

## Phase 1 — `kmet.libs.mcp` hierarchy (protocol + client + transports)

Extract the transport-neutral MCP client from `client.clj` — stdio (80),
streamable-http (151), request core (381), handshake/discovery (747), result
formatting (918) — plus the legacy SSE transport. Behavior preserved; era
neutrality deliberate (Phase 4 is 2026-07-28).

### 1.0. `kmet.libs.jsonrpc` additions (stdio substrate, time-boxed)

Use the shared lib instead of the bespoke stdio transport (`stdio-argv`,
`drain-stdout`, `drain-stderr`, `write-stdio-msg!`, channel wait). It already
provides line framing, pending map with timeouts, `:on-request`,
`:on-notification`, stderr tail, `last-used`, `alive?`, `close!`, and the
`connect-streams` injection seam.

One additive change: put the request `:id` in the timeout/request-error
ex-data (`::timeout` / `::request-error`), so the MCP client can send
`notifications/cancelled` with the abandoned request's id (`request!` allocates
the id internally today).

Per-request progress routing stays in the MCP client layer
(`progressToken → callback` map installed as the conn-level
`:on-notification` handler, pi's `progressRequests` pattern).

If this needs more than the `:id` addition, use the time-box rule above and
move the current stdio transport into the lib unchanged.

Tests in `test/kmet/libs/test_jsonrpc.clj`: id present on timeout; a
cancellation round-trip against the in-process fake (assert the server sees
`requestId`). lsp-adapter is unaffected (additive); run
`extensions/lsp-adapter/scripts/validate.bb`.

### 1.1. Layout and namespaces

```
src/kmet/libs/mcp/protocol.clj            kmet.libs.mcp.protocol
src/kmet/libs/mcp/client.clj              kmet.libs.mcp.client
src/kmet/libs/mcp/transport.clj           kmet.libs.mcp.transport
src/kmet/libs/mcp/transport/stdio.clj     kmet.libs.mcp.transport.stdio
src/kmet/libs/mcp/transport/http.clj      kmet.libs.mcp.transport.http
src/kmet/libs/mcp/transport/sse.clj       kmet.libs.mcp.transport.sse
```

- `protocol`: `default-request-timeout-ms`, `initialize-timeout-ms`,
  `list-page-timeout-ms`, `protocol-version`, `supported-protocol-versions`,
  `client-info`, `mcp-error`, `format-result` (content blocks →
  `{:text :is-error}`). This is where the era map, `_meta` decoration,
  `resultType`, and `-32022` handling land in Phase 4.
- `client`: `progress-token`, `request!`, `notify!`, `close!`, `alive?`,
  `last-used`, `establish!` (handshake + capability-gated catalog fetch),
  `connect!` (all three transports), `list-all-tools`, `list-all-prompts`,
  `get-prompt`, `list-all-resources`, `list-all-resource-templates`,
  `read-resource`, `expand-uri-template`. **Connection-shape hygiene**: the lib
  consumes an explicit connection subset (`:command :args :env :cwd :url
  :http-transport :request-timeout-ms`) or a `:connection` sub-map; the adapter
  maps its config onto it — no `:lifecycle`/`:direct-tools`/`:tool-prefix` keys
  leak into the lib. Auth stays injected via
  `:auth-headers`/`:on-401`/`:on-notification` (unchanged contract, so Phase 2
  slots in).
- `transport`: the conn contract (documented keys + the fns each transport
  provides: send request, notify, reply, cancel, close, alive?, last-used) and
  shared `header-value`.
- `transport.http`: current streamable-HTTP code (`base-http-headers`,
  `http-post!`, `parse-http-response`, response-body SSE parsing),
  `terminate-http-session!`, over `kmet.libs.http`. Response-body SSE parsing
  uses `kmet.libs.sse/parse-sse-line` instead of the hand-rolled loop in
  `read-sse-response`. Add a `:request-fn` injection point (pi injects `fetch`)
  so fast unit tests need no socket.
- `transport.sse`: the legacy binding (`sse-endpoint-url`, `drain-sse-stream`,
  `open-sse-stream!`, `request-sse!`), over `kmet.libs.sse/parse-sse-line` (and
  `stream-loop` where its idle/abort/cleanup semantics fit). Migrated as-is, no
  behavior improvements; docstring marks it deprecated and points at streamable
  HTTP.
- `transport.stdio`: wraps a `kmet.libs.jsonrpc` conn and implements the
  contract; cancel = `notify!` `notifications/cancelled` with the id.

`uri-escape` stays private to the client.

### 1.2. Extension facade

`client.clj` keeps its namespace and public surface as a **pure re-export** of
the lib (all three transports now live in the lib), so `core.clj`,
`tool_proxy.clj`, `auth.clj`, `prompts.clj`, and all six scripts are untouched:
`protocol-version`, `supported-protocol-versions`, `client-info`,
`progress-token`, `default-request-timeout-ms`, `initialize-timeout-ms`,
`list-page-timeout-ms`, `header-value`, `connect!`, `request!`, `notify!`,
`close!`, `alive?`, `last-used`, `list-all-*`, `get-prompt`, `read-resource`,
`expand-uri-template`, `format-result`. Use plain `(def x lib/x)` aliases.
The facade can be deleted in Phase 4 with callers pointed at the lib directly.

### 1.3. Wiring

- Add all six lib ns symbols to `libs-library-namespaces`
  (`src/kmet/app/extensions/context.cljc:222`).
- Fix `test/kmet/libs/test_self_contained.clj` `lib-files` to walk
  `src/kmet/libs` recursively (e.g. `(fs/glob "src/kmet/libs" "**")` filtered
  to `.clj`/`.cljc`/`.clj?`), so `mcp/transport/*.clj` is checked.
- Register new test namespaces in `tasks/kmet/tasks/runner.clj`
  (`all-namespaces`, pattern: `kmet.libs.test-jsonrpc` at :156).

### 1.4. Tests

- `test/kmet/libs/mcp/test_protocol.clj` — versions; `mcp-error` shape;
  `format-result` for text/error/image/`structuredContent`/empty; template
  escaping and expansion (missing var left in place).
- `test/kmet/libs/mcp/test_client.clj` — stdio over jsonrpc `connect-streams`:
  handshake, pagination (`nextCursor`), notification dispatch, server→client
  request reply, timeout + `notifications/cancelled` carrying the id, stale
  response, transport death. Real subprocess cases stay in
  `scripts/validate-client.bb` or get `^:slow`.
- `test/kmet/libs/mcp/test_transport_http.clj` — JSON and SSE response bodies,
  content-type branching, header lookup, 401 retry hook, via `:request-fn`
  injection. Socket-level end-to-end stays in `validate-client.bb` /
  `fake-http-mcp-server.bb`.
- `test/kmet/libs/mcp/test_transport_sse.clj` — endpoint event resolution
  (path vs JSON), message assembly, stream drop, against an injected stream;
  the existing SSE section of `validate-client.bb` covers the real server.

### 1.5. Gates

`bb test` and `bb test-ext` (new lib namespaces included), `bb lint`,
`bb format-check`, `bb check`, `bb check-bundled-extensions`; then the full
extension suite (`scripts/validate-all.bb`, or individually):

```
DATA_JSON="$HOME/.m2/repository/org/clojure/data.json/2.4.0/data.json-2.4.0.jar"
bb -cp ../../src:src:$DATA_JSON scripts/validate-client.bb scripts/fake-mcp-server.bb scripts/fake-http-mcp-server.bb
bb -cp ../../src:src:$DATA_JSON scripts/validate-config.bb
bb -cp ../../src:src:$DATA_JSON scripts/validate-panel.bb
bb -cp ../../src:src:$DATA_JSON scripts/validate-oauth.bb scripts/fake-oauth-server.bb
bb -cp ../../src:src:$DATA_JSON scripts/validate-script.bb scripts/fake-mcp-server.bb
bb -cp ../../src:src:$DATA_JSON scripts/e2e.bb scripts/fake-mcp-server.bb
```

Also `extensions/lsp-adapter/scripts/validate.bb` (jsonrpc change) and
`jolt test` (or the host's gate) for the libs.

---

## Phase 2 — auth (deferred)

`kmet.libs.mcp.auth`: challenge parse/record, RFC 8707 resource
canonicalization, issuer-keyed credential store incl. keyring backends,
pre-emptive refresh + 401-once retry header provider — built on
`kmet.libs.oauth`. Extension keeps config mapping, status, interaction map,
`/mcp auth|logout`. Include the 2026 hardening: issuer binding (SEP-2352;
migrate `mcp-oauth.edn` from server-keyed entries), `iss` validation
(RFC 9207), DCR `application_type` (SEP-837).

## Phase 3 — optional hygiene

`core.clj` lifecycle → `server.clj` / `direct_tools.clj` / `commands.clj`;
`tool_proxy.clj` → `search.clj` + `status.clj`; drop
`kmet.libs.mcp.transport.sse` once the deprecation window closes.

## Phase 4 — 2026-07-28 protocol work (deferred, the end goal)

Inside `kmet.libs.mcp`: era map on the conn, per-request `_meta`, stdio
`server/discover` probe + `initialize` fallback, HTTP modern-request/400-body
detection + `-32022` retry, `Mcp-Method`/`Mcp-Name`, no session header,
`resultType`/MRTR refusal, `subscriptions/listen` lifecycle; fakes gain a
modern mode; `scripts/validate-protocol.bb`. **Era caching**: store the era in
the metadata cache entry (`:protocol-era`) keyed to the config fingerprint — a
different `:command`/`:url` can be a different server binary — and re-probe on
any failure instead of trusting the cached assumption. `transport.sse` is not
touched (legacy connections negotiate legacy versions only). Delete the
extension facade here (callers go straight to the lib). Depends on Phase 1's
era-neutral seam and Phase 2's auth plumbing.

## Non-goals

- No `config`, `metadata`, `output_guard`, or `tool_proxy` in `kmet.libs`.
- No server-side MCP in `kmet.libs.mcp` (client use only).
- Don't start Phase 3 hygiene before Phase 4 unless something there actually
  blocks it.
- Don't fix the collision-display divergence anywhere but 0.2.
- Don't invest in `transport.sse` beyond the migration (deprecated).

## Risks

- `kmet.libs.*` is injected by reference into every extension context and
  required once per host start: keep the lib free of UI/tool/config deps (it is
  today).
- The `test-self-contained` recursion fix is part of Phase 1 — without it the
  new `mcp/transport/` files escape the guard.
- The lib API becomes public for extensions; keep internals private and
  document the surface in the ns docstrings from the start.
- The jsonrpc change is shared with lsp-adapter — additive only, with its own
  tests and its validation script.
- Phase 0 changes registered/display names in collision cases only; the scripts
  that assert names (`e2e.bb`, `validate-panel.bb`, `validate-script.bb`) must
  be green at each step.
- `assign-names` changes names for collision groups on every catalog refresh —
  correct, but `script-tool-records` names can change mid-session when a server
  advertises a new colliding tool. Accepted; if it ever bites, pin names per
  server at first sight instead of recomputing.

## Ordered checklist

- [x] baseline: run the six scripts, add `scripts/validate-all.bb`
- [x] 0.1 `names.clj` primitives + callers switched + originals deleted (move only)
- [x] 0.2 `assign-names` + `:tool-names` + proxy `display-name` (the fix)
- [x] 0.3 `validate-names.bb` + six extension scripts + lint/format
- [ ] 1.0 jsonrpc `:id` in ex-data (time-boxed) + tests; lsp validation green
- [ ] 1.1 lib files in landing order: protocol → transport.http → transport.sse → transport.stdio
- [ ] 1.2 extension facade (pure re-export)
- [ ] 1.3 whitelist + self-contained guard recursion + test registration
- [ ] 1.4 lib tests (incl. transport sse/http)
- [ ] 1.5 full gates + extension scripts + lsp validation
- [ ] 2 auth extraction + hardening
- [ ] 3 optional hygiene
- [ ] 4 2026-07-28 protocol work (separate plan)
