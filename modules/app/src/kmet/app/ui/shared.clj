(ns kmet.app.ui.shared
  "Toolkit-neutral app view helpers.

   Put here only code that neither requires `kmet.tui.*` nor
   `kmet.tk.*`: data shaping, formatting, truncation, content-block
   models, and similarly backend-independent view support. Actual surfaces
   live in `kmet.app.ui.tui.*` and `kmet.app.ui.gui.*`.

   Everything in this namespace is still app view-layer code for
   `kmet.modes.test-headless-guard` purposes, so it must not be required by
   print or RPC modes directly.")
