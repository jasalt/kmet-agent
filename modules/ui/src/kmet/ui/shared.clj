(ns kmet.ui.shared
  "Toolkit-neutral app view helpers.

   Put here only code that neither requires `kmet.tui.*` nor
   `kmet.tk.*`: data shaping, formatting, truncation, content-block
   models, and similarly backend-independent view support. Actual surfaces
   live in `kmet.ui.tui.*`; the Tk sample UI lives outside this module
   (`kmet.gui-sample.*`, in modules/tk) so nothing here depends on Tk.

   Everything in this namespace is still app view-layer code, so it must not be
   required by print or RPC modes directly (the headless guard holds that).")
