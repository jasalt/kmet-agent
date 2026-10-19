(ns kmet.ai.slots
  "One-slot registration helpers: a var holds F, unset by default.

   Used by kmet.ai.auth for the two sources it must not require (the model
   registry's config key and the OAuth record). Provider-event hooks are not
   in here: they are `kmet.ai.llm/send-message` options, supplied per call.")

(defn make-slot
  "Create an empty slot (an atom holding nil)."
  []
  (atom nil))

(defn set-slot!
  "Install F as the slot's handler. Returns nil."
  [slot f]
  (reset! slot f)
  nil)

(defn apply-slot
  "Apply SLOT to args, returning (apply f args) when installed, or nil."
  [slot & args]
  (when-let [f @slot]
    (apply f args)))
