(ns kmet.libs.terminal
  "Terminal escape-sequence helpers shared across layers (ANSI stripping)."
  (:require [clojure.string :as str]))

(def ANSI-CODE-RE
  "ANSI CSI (ESC [ … final byte) and OSC (ESC ] … BEL/ST/0x9C) sequences."
  #"\u001b\[[0-9;]*[a-zA-Z]|\u001b\][^\u0007\u001b\u009c]*(?:\u001b\\|\u0007|\u009c)")

(defn strip-ansi-codes
  "Strip ANSI CSI/OSC sequences from S. This portable helper uses the regex
   form, matching the terminal layer's default implementation; front ends
   with a host-optimal scanner may still use kmet.tui.utils for width work."
  [s]
  (str/replace s ANSI-CODE-RE ""))
