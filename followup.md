# followup — context-replaced tool pairing

Open follow-ups from the empty-tool-title fix: `chat-history-rebuild!` now
pairs assistant `:tool-calls` with their `:tool` result messages
(`kmet.app.ui.chat-history/pair-tool-messages`), so a tool execution rebuilt
from a replaced context renders its call line (name + args) — and a quiet
one-liner keeps its title instead of rendering blank.

Verified by unit tests (pairing, out-of-order parallel results, recorded
edit diff, quiet toggle, orphan result) and an event-handler test
(`context-replaced-pairs-tool-calls-with-results-live`). Not yet smoke-tested
in a live session: a `/compact` on a session with a tool-call tail, then
ctrl+o (needs an LLM summarization call).

## 1. Unify tool-call/result pairing

`kmet.modes.interactive.session-admin/replay-branch!` and
`kmet.app.ui.chat-history/pair-tool-messages` implement the same assistant
`:tool-calls` ↔ `:tool` result correlation (by tool-call id) in two shapes:
component construction (replay) vs message-map pairing (rebuild). Two
implementations of one invariant can drift — replay handles errored/aborted
calls with the failure text and keeps `pending-tools`; rebuild pre-builds
message maps.

Options:

- extract a shared correlation helper (call-id → call) both use;
- or make the rebuild render through the replay path from the final message
  vector (pi always replays from the session).

Precondition: replay's extra pi-parity surface (custom entries/messages,
bash replay, compaction cost notices, the "Session compacted N times"
status) must stay unaffected. The `:context-replaced` handler currently
duplicates `run-message-renderer` + notices for the raw event messages, so
a switch is not mechanical.

## 2. Paired tool messages drop `:tool-name`

`chat-history-get-messages` output for a rebuilt tool now carries
`:name`/`:args` (the live message shape) instead of `:tool-name`. No
production caller reads these today — persistence uses the session, `/tree`
reads session entries. If a consumer of that shape appears, either keep
`:tool-name` on `call-message` too or document the shape.

## 3. Unpaired call without a result renders pending

Decision: **leave as-is**.

`pair-tool-messages` keeps a synthetic component for an assistant call that
has no matching result; it renders as pending (no ended-at). Unreachable
through both real `:context-replaced` emit paths — `drop-incomplete-tool-calls`
prunes unanswered calls before the event is emitted — but a direct
`chat-history-rebuild!` caller could hit it.

We do not inject failure text here because the rebuild path lacks the
assistant entry's `:stop-reason` / `:error-message` metadata needed to choose
pi's aborted vs. error wording. `replay-branch!` still synthesizes that text
from the assistant entry, and production callers rely on
`drop-incomplete-tool-calls` to remove the case before rebuild.

## 4. Live compaction regression test

Cover the end-to-end path with a real `compact-context!` tail (post-compaction
context with tool calls, then a display-mode toggle). The summarizer is
LLM-backed, so it needs a stubbed `summarize!` in the agent loop.

## 5. Test helper duplication (minor)

`test/kmet/modes/test_interactive.clj` strips ANSI with a local regex while
`test/kmet/app/ui/test_chat_history.clj` has `strip-ansi`. Consider a shared
helper in `kmet.test-utils` if a third copy appears.
