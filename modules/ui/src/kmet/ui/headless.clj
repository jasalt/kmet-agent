(ns kmet.ui.headless
  "The headless `:ui` provider: the first implementation of `kmet.app.ui`'s
   contract, and the one the profiles that render nothing stack.

   It wraps the ABI's own degraded answers (`extensions/ui-request-defaults`)
   rather than deciding again what `ask is not yes` means: a dialog kind has one
   default answer in the ABI, and every provider starts from it — a promotion
   from headless to a terminal changes who answers, not what an unanswered one
   counts as. Nothing renders, `:render-tree` is a no-op, and no optional
   capability is offered — a consumer asking for `:widget-set` or `:notify` sees
   the `:capabilities` it did not declare.

   `provider` is a value so the contract test can check it without mounting it;
   `plugin` is the row that mounts it (`:bundle/headless`)."
  (:require [kmet.app.extensions.core :as extensions]
            [kmet.app.ui :as ui]
            [kmet.srce.core :as srce]))

(def provider
  "The provider `kmet.app.ui/provide!` validates: nothing to render, dialogs
   answered by the ABI's per-kind defaults, no optional capability."
  {:kind :headless
   :render-tree (fn [_tree] nil)
   :request! (fn [_ctx request] (extensions/ui-request-default (:kind request)))
   :capabilities #{}})

(srce/defplugin plugin
  {:name :ui/headless
   :provide #{:ui}
   :apply (fn [ctx _] (ui/provide! ctx :ui/headless provider))})
