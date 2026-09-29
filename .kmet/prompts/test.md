---
description: Run the full gate (lint + format-check + bb test + bb test-ext + jolt test + jolt test-ext) and fix failures
argument-hint: "[scope or extra instructions]"
---
Run the full validation gate for this project and fix any failures, strictly in this order:

1. `bb format-check` — if it fails, run `bb format` to fix the formatting, then re-check
2. `bb lint` — must pass with 0 errors, warnings, and info findings
3. `bb test` — the non-slow test suite on babashka
4. `bb test-ext` — the slow (^:slow) tests on babashka
5. `jolt test` — the non-slow test suite on jolt
6. `jolt test-ext` — the slow (^:slow) tests on jolt

Run the six steps sequentially, one at a time, not just to the first failure. Fix findings as they appear, but never re-run work a fix did not invalidate:

- Iterate on a failure with the narrowest command: the failing test var or namespace (`bb test <var>`, `jolt test <var>`, same with test-ext), or the changed-file task (`bb lint-changed`, `bb format-check-changed`, `bb test-changed`, `bb test-ext-changed`). Do not repeat a whole step after every edit.
- A step that is green stays green. Re-run it only when a later edit touched files it covers: any edit invalidates format-check and lint for that file; a `src/` or `tasks/` edit can affect any test suite on either host; a `jolt/` edit affects jolt only; a test edit affects its own suite; a `bb format` fix (whitespace only) invalidates format-check alone. Use `bb changed` to see what was touched.
- Never restart the gate from step 1, and never re-run a step "just to be safe". Re-verify an invalidated step with its changed-file task — the closure covers the edit — and use the full step only when the changed task cannot cover it.
- Before finishing, every step must be green in the current state: either it passed and nothing it covers changed since, or it was re-verified after the last edit that touched it. Report what ran, what each result covers, and which steps you deliberately did not re-run.

If extra instructions were given after /test, apply them — e.g. a narrower scope like "lint only" or a specific test namespace. The full gate is the default; this command is the explicit instruction to run it.
