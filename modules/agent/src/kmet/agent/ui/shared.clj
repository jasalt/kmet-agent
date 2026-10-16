(ns kmet.agent.ui.shared
  "Toolkit-neutral app view helpers.

   Put here only code that neither requires `kmet.tui.*` nor
   `kmet.tk.*`: data shaping, formatting, truncation, content-block
   models, and similarly backend-independent view support. Actual surfaces
   live in `kmet.agent.ui.tui.*` and `kmet.agent.ui.gui.*`.

   Everything in this namespace is still app view-layer code for
   `kmet.agent.modes.test-headless-guard` purposes, so it must not be required by
   print or RPC modes directly.")
