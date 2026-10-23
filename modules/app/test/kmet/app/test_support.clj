(ns kmet.app.test-support
  "Shared helpers for the app's entry-point tests."
  (:require [kmet.app.core :as core]))

(defn run-main
  "The `{:code :out :err}` of kmet2's entry on ARGS — `main` returns the exit
   code and prints, so a command is read without forking a process."
  [args]
  (let [code (atom nil)
        err (java.io.StringWriter.)
        out (binding [*err* err]
              (with-out-str (reset! code (core/main args))))]
    {:code @code :out out :err (str err)}))
