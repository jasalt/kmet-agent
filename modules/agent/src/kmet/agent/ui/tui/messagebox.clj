(ns kmet.agent.ui.tui.messagebox
  "Shared box-theme plumbing for custom message components whose children are rebuilt when theme or expansion changes."
  (:require [kmet.tui.components.box :as box]
            [kmet.tui.theme :as theme]))

(defn themed-box!
  "Set the box background for THM and rebuild COMP's content. REBUILD is the component-specific callback."
  [comp thm rebuild]
  (box/box-set-bg-fn @(:box comp) #(theme/bg thm :custom-message-bg %))
  (rebuild comp @(:expanded-atom comp)))
