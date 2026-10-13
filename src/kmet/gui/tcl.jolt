(ns kmet.gui.tcl
  "The jolt.ffi Tcl/Tk adapter for kmet.gui.tk (the \"hiccup-tk\" backend).

   Loads libtcl/libtk, creates one interpreter, and exposes it to the
   portable backend through the adapter map:

     :prepare! (fn [] nil)              ; package require Tk (needs a display)
     :eval     (fn [script] -> string)  ; Tcl_Eval, throws on TCL_ERROR
     :command! (fn [name handler])      ; Tcl_CreateObjCommand over one
                                        ; foreign-callable trampoline
     :run-loop (fn [pump])              ; 25 ms after-timer + vwait

   The trampoline is one C-callable Jolt fn held for the process; each
   registered command answers as `(handler argv)` with the returned string
   as the Tcl result. Tcl invokes it from inside the vwait call, which is
   why the callable is :collect-safe and the eval that parks is :blocking
   (the same contract glimmer-gtk uses for g_application_run).

   A .jolt file — Jolt code by construction; babashka cannot load it."
  (:require [jolt.ffi :as ffi]
            [kmet.gui.tk :as tk])
  (:refer-clojure :exclude [run!]))

;; ─── Bindings ─────────────────────────────────────────────────────────────

(ffi/defcfn ^:private tcl-find-executable "Tcl_FindExecutable" [:string] :void)
(ffi/defcfn ^:private tcl-create-interp "Tcl_CreateInterp" [] :pointer)
(ffi/defcfn ^:private tcl-delete-interp "Tcl_DeleteInterp" [:pointer] :void)
(ffi/defcfn ^:private tcl-init "Tcl_Init" [:pointer] :int)
(ffi/defcfn ^:private tcl-eval "Tcl_Eval" [:pointer :string] :int)
;; The parking call must not hold a Jolt string across the blocking window
;; (the collector may move it), so the blocking variant takes a C copy of
;; the script and frees it around the call.
(ffi/defcfn ^:private tcl-eval-blocking "Tcl_Eval" [:pointer :pointer] :int :blocking)
(ffi/defcfn ^:private tcl-create-obj-command "Tcl_CreateObjCommand"
  [:pointer :string :pointer :pointer :pointer] :pointer)
(ffi/defcfn ^:private tcl-get-string "Tcl_GetString" [:pointer] :string)
(ffi/defcfn ^:private tcl-new-string-obj "Tcl_NewStringObj" [:string :int] :pointer)
(ffi/defcfn ^:private tcl-set-obj-result "Tcl_SetObjResult" [:pointer :pointer] :void)
(ffi/defcfn ^:private tcl-get-string-result "Tcl_GetStringResult" [:pointer] :string)

(def ^:private tcl-ok 0)

(defn- load-first!
  "Load the first library name that resolves; throw when none does."
  [names]
  (or (some (fn [n]
              (try
                (ffi/load-library n)
                n
                (catch Throwable _ nil)))
            names)
      (throw (ex-info (str "kmet.gui.tcl: none of " (pr-str names)
                           " could be loaded — install Tcl/Tk")
                      {:type :kmet.gui.tcl/missing-library
                       :tried names}))))

;; ─── Interpreter state ────────────────────────────────────────────────────

(defonce ^:private interp (atom nil))
(defonce ^:private handlers (atom {}))
(defonce ^:private trampoline-callable (atom nil))
(defonce ^:private tk-ready? (atom false))

(defn- argv
  "Read the Tcl_Obj* array OBJV (OBJC entries) as strings."
  [objv objc]
  (let [psize (ffi/sizeof :pointer)]
    (mapv (fn [i] (tcl-get-string (ffi/read objv :pointer (* i psize))))
          (range objc))))

(defn- trampoline-proc
  "The one C entry point behind every command: answer with the registered
   handler's string (unknown commands answer empty)."
  [_client interp* objc objv]
  (let [args (argv objv objc)
        handler (get @handlers (first args))]
    (when handler
      (let [result (try
                     (str (or (handler args) ""))
                     (catch Throwable t
                       (str "kmet callback error: " (ex-message t))))]
        (tcl-set-obj-result interp* (tcl-new-string-obj result -1))))
    tcl-ok))

(defn- ensure-trampoline! []
  (or @trampoline-callable
      (reset! trampoline-callable
              (ffi/foreign-callable trampoline-proc
                                    [:pointer :pointer :int :pointer] :int
                                    :collect-safe))))

(defn- ensure-interp!
  "Create the interpreter once (libtcl loaded). Returns it."
  []
  (or @interp
      (do
        (load-first! ["libtcl8.6.so" "libtcl9.0.so" "libtcl.so"
                      "libtcl8.6.dylib" "libtcl9.0.dylib" "libtcl.dylib"
                      "tcl86.dll" "tcl90.dll" "tcl86t.dll"
                      "libtcl8.6.dll" "libtcl9.0.dll"])
        (tcl-find-executable "jolt")
        (let [i (tcl-create-interp)
              rc (tcl-init i)]
          (when-not (= rc tcl-ok)
            (throw (ex-info (str "kmet.gui.tcl: Tcl_Init failed: "
                                 (tcl-get-string-result i))
                            {:type :kmet.gui.tcl/init-failed})))
          (reset! interp i)
          i))))

(defn- tcl-eval!
  "Evaluate SCRIPT, throwing with Tcl's result on error. BLOCKING? marks
   the call as parking in Tcl's event loop (vwait)."
  [script blocking?]
  (let [i (ensure-interp!)
        rc (if blocking?
             (let [p (ffi/string->ptr script)]
               (try
                 (tcl-eval-blocking i p)
                 (finally
                   (ffi/free p))))
             (tcl-eval i script))]
    (when-not (= rc tcl-ok)
      (throw (ex-info (str "kmet.gui.tcl: " (tcl-get-string-result i))
                      {:type :kmet.gui.tcl/eval-failed :script script})))
    (or (tcl-get-string-result i) "")))

(defn- command!
  "Register NAME's HANDLER (a fn of the argv vector -> string)."
  [name handler]
  (let [i (ensure-interp!)
        _ (swap! handlers assoc name handler)
        cb (ensure-trampoline!)]
    (tcl-create-obj-command i name cb 0 0)
    nil))

(defn- prepare!
  "Load the Tk package into the interpreter (needs a display)."
  []
  (when-not @tk-ready?
    (if (some (fn [n] (try (ffi/load-library n) true (catch Throwable _ nil)))
              ["libtk8.6.so" "libtk9.0.so" "libtk.so"
               "libtk8.6.dylib" "libtk9.0.dylib" "libtk.dylib"
               "tk86.dll" "tk90.dll" "tk86t.dll"
               "libtk8.6.dll" "libtk9.0.dll"])
      (do
        (tcl-eval! "package require Tk" false)
        (reset! tk-ready? true))
      (throw (ex-info "kmet.gui.tcl: libtk could not be loaded"
                      {:type :kmet.gui.tcl/missing-library})))))

(defn- run-loop!
  "Register the pump command, arm a 25 ms re-arming after-timer, and park
   in vwait until the window closes. PUMP runs on the Tk thread."
  [pump]
  (command! "kmet::pump" (fn [_argv] (pump) ""))
  (command! "kmet::close" (fn [_argv] (tcl-eval! "set ::kmet::done 1" false) ""))
  (tcl-eval! (str "proc ::kmet::tick {} { kmet::pump; after 25 ::kmet::tick }\n"
                  "after 25 ::kmet::tick\n"
                  "wm protocol . WM_DELETE_WINDOW kmet::close\n"
                  "set ::kmet::done 0")
             false)
  (tcl-eval! "vwait ::kmet::done" true)
  nil)

;; ─── Public entry ─────────────────────────────────────────────────────────

(defn adapter
  "The adapter map for kmet.gui.tk."
  []
  {:id :jolt-tcl
   :prepare! prepare!
   :eval (fn [script] (tcl-eval! script false))
   :command! command!
   :run-loop run-loop!})

(defn run!
  "Install the jolt.ffi adapter and run the Tk backend on TREE, blocking
   until the window closes. OPTIONS: :title :width :height."
  [tree & [options]]
  (ensure-interp!)
  (tk/set-adapter! (adapter))
  (tk/run! tree options))

(defn stop!
  "Delete the interpreter (tests, orderly shutdown)."
  []
  (when-some [i @interp]
    (tcl-delete-interp i)
    (reset! interp nil)
    (reset! tk-ready? false)
    (reset! handlers {})
    (reset! trampoline-callable nil)))
