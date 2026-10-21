# Fix model generation warnings

Use this when `bb generate-models` prints `Warning: ... did not resolve`.

## Goal

Make generation warning-free and keep committed catalogs consistent with the
generator. Warnings mean a CommandCode canonical ref points at a catalog id that
no longer exists, so the affected endpoint silently falls back to conservative
defaults.

## Steps

1. Run generation and capture warnings:

   ```sh
   bb generate-models
   ```

2. Each warning has the form:

   ```text
   Warning: CommandCode canonical ref <commandcode-id> -> <provider> <canonical-id> did not resolve — falling back to conservative defaults.
   ```

3. Open `modules/ai/src/kmet/ai/model_gen.clj` and find the matching entry in
   `commandcode-canonical-refs`.

4. Find a catalog that still serves the same weights. Search committed catalogs
   for the unresolved canonical id or the CommandCode endpoint id:

   ```sh
   grep -R "<canonical-id-or-endpoint-id>" modules/ai/src/kmet/ai/model_data/*.edn
   ```

   Prefer a catalog whose entry:
   - has the same or closest model id
   - has `:reasoning true` when the endpoint should reason
   - has sensible `:input` and `:max-tokens`
   - is already used as canonical for sibling models, when possible

5. Update the ref in `commandcode-canonical-refs` to `[<provider> "<new-id>"]`.

6. If the endpoint is endpoint-bound rather than model-bound, keep it in
   `commandcode-no-compat-refs` so `:thinking-format` is not transferred.

7. Update any tests that assert the old ref or old canonical id, especially
   `modules/ai/test/kmet/ai/test_model_data.clj`.

8. Regenerate catalogs:

   ```sh
   bb generate-models
   ```

9. Verify there are no warnings and committed data is valid:

   ```sh
   bb generate-models 2>&1 | grep -i warning
   bb check-model-data
   ```

10. Run the affected tests:

    ```sh
    bb test-changed
    ```

## Notes

- Do not hand-edit generated provider catalogs to silence warnings; fix the
  canonical ref in `model_gen.clj` and regenerate.
- A ref must point to an id that exists in the selected provider catalog.
- Keep changes minimal: only repoint unresolved refs and update affected tests.
