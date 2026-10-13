(ns kmet.tui.alias
  "Qualified-keyword tag aliases — the TUI-facing facade over the
   toolkit-agnostic registry in kmet.libs.hikap.alias.

   The registry, resolution and defalias semantics are documented on
   kmet.libs.hikap.alias; this namespace exists so extension API code, app code
   and tests keep requiring kmet.tui.alias."
  (:require [kmet.libs.hikap.alias :as lib]))

(def registered lib/registered)
(def lookup lib/lookup)
(def register! lib/register!)
(def unregister! lib/unregister!)
(def reset-registry! lib/reset-registry!)
(def head lib/head)

(defmacro defalias
  "Define NAME as an alias in the current namespace — see
   kmet.libs.hikap.alias/defalias."
  [name & forms]
  `(kmet.libs.hikap.alias/defalias ~name ~@forms))
