(ns kmet.gui.tcl
  "The host FFI Tcl/Tk adapter for kmet.gui.tk (the \"hiccup-tk\" backend).

   Loads libtcl/libtk — Tcl/Tk 9.1 first; when only an older ABI loads it
   warns on stderr and falls back through 9.0 and 8.6 — creates one
   interpreter, and exposes it to the portable backend through the adapter
   map:

     :prepare! (fn [] nil)              ; load libtk + Tk_Init (needs a display)
     :eval     (fn [script] -> string)  ; Tcl_Eval, throws on TCL_ERROR
     :command! (fn [name handler])      ; Tcl_CreateObjCommand over one
                                        ; callback trampoline
     :run-loop (fn [pump])              ; 25 ms after-timer + vwait

   Tk is a Tcl package, not a standalone toolkit: libtk imports libtcl
   and contributes its widget commands to the interpreter libtcl creates,
   so both libraries are loaded (libtk in `prepare!`, after the
   interpreter exists) from the one Tcl/Tk installation. The Tk major
   version must match Tcl's — Tk 9.x runs only on Tcl 9.x.

   `prepare!` resolves the Tk DLL on PATH, mounts the zipfs library its
   build appended to the DLL, and loads it through Tcl's `load`; Tk_Init
   then finds tk.tcl with no compiled-in install prefix, so the DLLs are
   standalone and PATH-resolved. A system installation whose library
   Tk_Init finds through its own paths needs none of that: the name-level
   FFI preload is left to Tk_Init when the absolute path is not found.

   The trampoline is one C-callable fn held for the process; each
   registered command answers as `(handler argv)` with the returned string
   as the Tcl result. Tcl invokes it from inside the vwait call, so on Jolt
   the callable is :collect-safe and the eval that parks is :blocking (the
   same contract glimmer-gtk uses for g_application_run).

   babashka.ffi and jolt.ffi expose the same FFM-style seam: both build the
   trampoline with `callback`, Jolt keeping it :collect-safe across the
   parked vwait. The host differences are narrow and marked below —
   babashka hands callback pointers to C arrays zero-size (so `argv`
   reinterprets them) and a Jolt string may move across a blocking call (so
   the parking eval passes Tcl a C copy, while babashka's :string argument
   is already call-scoped)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [#?(:bb babashka.ffi :jolt jolt.ffi) :as ffi]
            [kmet.gui.tk :as tk])
  (:refer-clojure :exclude [run!]))

;; ─── Bindings ─────────────────────────────────────────────────────────────

;; Tcl 9 keeps Tcl_Eval / Tcl_GetString / Tcl_GetStringResult as macros over
;; these calls, so the adapter binds the real symbols and works against
;; both 8.6 and 9.1. TCL_INDEX_NONE/string lengths are a Tcl_Size (ptrdiff_t
;; since Tcl 9), hence :int64 — the low 32 bits are the int length Tcl 8.6
;; reads.
(ffi/defcfn ^:private tcl-find-executable "Tcl_FindExecutable" [:string] :void)
(ffi/defcfn ^:private tcl-create-interp "Tcl_CreateInterp" [] :pointer)
(ffi/defcfn ^:private tcl-delete-interp "Tcl_DeleteInterp" [:pointer] :void)
(ffi/defcfn ^:private tcl-init "Tcl_Init" [:pointer] :int)
(ffi/defcfn ^:private tcl-eval-ex "Tcl_EvalEx" [:pointer :string :int64 :int] :int)
;; The parking call must not hold a Jolt string across the blocking window
;; (the collector may move it), so the blocking variant takes a C copy of
;; the script and frees it around the call.
#?(:jolt
   (ffi/defcfn ^:private tcl-eval-ex-blocking "Tcl_EvalEx"
     [:pointer :pointer :int64 :int] :int :blocking))
(ffi/defcfn ^:private tcl-create-obj-command "Tcl_CreateObjCommand"
  [:pointer :string :pointer :pointer :pointer] :pointer)
(ffi/defcfn ^:private tcl-get-string-from-obj
  "Tcl_GetStringFromObj" [:pointer :pointer] :string)
(ffi/defcfn ^:private tcl-get-obj-result "Tcl_GetObjResult" [:pointer] :pointer)
(ffi/defcfn ^:private tcl-new-string-obj "Tcl_NewStringObj" [:string :int64] :pointer)
(ffi/defcfn ^:private tcl-set-obj-result "Tcl_SetObjResult" [:pointer :pointer] :void)
;; Tk_Init is what Tcl's `load` reaches through Tk's pkgIndex; calling it
;; directly frees the layout from package-path discovery.
(ffi/defcfn ^:private tk-init "Tk_Init" [:pointer] :int)

(def ^:private tcl-ok 0)
(def ^:private tcl-index-none -1)

(defn- tcl-result
  "The interpreter's current result as a string."
  [i]
  (tcl-get-string-from-obj (tcl-get-obj-result i) ffi/null))

(defn- eval-script
  "Tcl_EvalEx with Tcl_Eval's macro semantics: the whole script, no flags."
  [i script]
  (tcl-eval-ex i script tcl-index-none 0))

;; ─── Library search ───────────────────────────────────────────────────────

(defn- path-dirs
  "The directories a bare library name is searched in: PATH entries in
   order, then the process's working directory."
  []
  (let [sep (re-pattern (java.util.regex.Pattern/quote
                         (System/getProperty "path.separator")))
        path (System/getenv "PATH")]
    (concat (when (seq path) (str/split path sep))
            [(System/getProperty "user.dir")])))

(defn- find-library
  "The absolute path of a shared library NAME found on PATH (or in the
   working directory), or nil. The Windows loader also searches the
   executable's directory, which PATH covers in kmet's deployments."
  [name]
  (some (fn [dir]
          (when (seq dir)
            (let [f (fs/path (str/replace dir #"^\"|\"$" "") name)]
              (when (fs/regular-file? f)
                (str (fs/absolutize f))))))
        (path-dirs)))

(defn- load-tk-script
  "A Tcl script that mounts PATH's appended zipfs library at
   //zipfs:/lib/tk and loads PATH as the Tk package. The mount makes the
   DLL standalone — Tk_Init finds tk.tcl in its own zip, with no
   compiled-in install prefix; `zipfs` is an invalid command when Tcl was
   built without zipfs, which the catch swallows."
  [path]
  (str "catch {zipfs mount " (tk/tcl-quote path) " //zipfs:/lib/tk}\n"
       "load " (tk/tcl-quote path) " Tk"))

(def ^:private tcl-libs
  "Tcl shared libraries by ABI, newest first; :generic is a bare soname."
  {:9.1 ["libtcl9.1.so" "libtcl9.1.dylib" "libtcl9.1.dll" "libtcl91.dll"
         "tcl91.dll" "tcl91t.dll"]
   :9.0 ["libtcl9.0.so" "libtcl9.0.dylib" "libtcl9.0.dll" "libtcl90.dll"
         "tcl90.dll" "tcl90t.dll"]
   :8.6 ["libtcl8.6.so" "libtcl8.6.dylib" "libtcl8.6.dll" "libtcl86.dll"
         "tcl86.dll" "tcl86t.dll"]
   :generic ["libtcl.so" "libtcl.dylib"]})

(def ^:private tk-libs
  "Tk shared libraries, the same ABI keys as tcl-libs. Tk 9.x's Windows
   build names the DLL tcl9tk<ver>.dll beside the plain tk<ver>.dll."
  {:9.1 ["libtk9.1.so" "libtk9.1.dylib" "libtk9.1.dll" "libtk91.dll"
         "tcl9tk91.dll" "tk91.dll" "tk91t.dll"]
   :9.0 ["libtk9.0.so" "libtk9.0.dylib" "libtk9.0.dll" "libtk90.dll"
         "tcl9tk90.dll" "tk90.dll" "tk90t.dll"]
   :8.6 ["libtk8.6.so" "libtk8.6.dylib" "libtk8.6.dll" "libtk86.dll"
         "tk86.dll" "tk86t.dll"]
   :generic ["libtk.so" "libtk.dylib"]})

(def ^:private abi-order [:9.1 :9.0 :8.6 :generic])

(defn- try-load
  "The first of NAMES whose shared library loads, or nil."
  [names]
  (some (fn [n]
          (try
            (ffi/load-library n)
            n
            (catch Throwable _ nil)))
        names))

(defn- warn!
  "Write a one-line loader warning to stderr."
  [message]
  (binding [*out* *err*]
    (println message)))

(defn- load-lib
  "Load a Tcl/Tk shared library for WHAT (either Tcl or Tk) and return
   [abi name]. ABIS are keys into LIBS, most-preferred first; the first
   ABI that is not :9.1 warns — 9.1 is the target, older libraries are a
   fallback. Throws when nothing loads."
  [what libs abis]
  (if-let [[abi n] (some (fn [abi]
                           (when-let [n (try-load (get libs abi))]
                             [abi n]))
                         abis)]
    (do (when-not (= abi :9.1)
          (warn! (str "kmet.gui.tcl: Tcl/Tk 9.1 " what " library not found; "
                      "falling back to " n)))
        [abi n])
    (throw (ex-info (str "kmet.gui.tcl: no " what " shared library could be "
                         "loaded — install Tcl/Tk 9.1 (8.6 also works)")
                    {:type :kmet.gui.tcl/missing-library
                     :tried (mapcat #(get libs %) abis)}))))

;; ─── Interpreter state ────────────────────────────────────────────────────

(defonce ^:private interp (atom nil))
(defonce ^:private handlers (atom {}))
(defonce ^:private tcl-abi (atom nil))

(defonce ^:private trampoline-arena (atom nil))
(defonce ^:private trampoline-callable (atom nil))
(defonce ^:private tk-ready? (atom false))

(defn- argv
  "Read the Tcl_Obj* array OBJV (OBJC entries) as strings. babashka hands
   callbacks zero-size pointers, so the array is given its real size
   first."
  [objv objc]
  (let [psize (ffi/sizeof :pointer)
        objv* #?(:bb (ffi/reinterpret objv (* objc psize))
                 :jolt objv)]
    (mapv (fn [i] (tcl-get-string-from-obj
                   (ffi/read objv* :pointer (* i psize)) ffi/null))
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
        (tcl-set-obj-result interp* (tcl-new-string-obj result tcl-index-none))))
    tcl-ok))

(defn- ensure-trampoline!
  "The process-lifetime trampoline pointer. The arena holds it: Jolt's
   collector may run while the parked vwait waits for a callback, so the
   callable is :collect-safe there; babashka's arena keeps the Clojure fn
   reachable."
  []
  (or @trampoline-callable
      (let [arena (ffi/global-arena)
            ;; the argtypes vector is literal on purpose: Jolt's callback is
            ;; a macro and does not take it through a local
            callable #?(:bb (ffi/callback arena trampoline-proc
                                          [:pointer :pointer :int :pointer] :int)
                        :jolt (ffi/callback arena trampoline-proc
                                            [:pointer :pointer :int :pointer] :int
                                            :collect-safe))]
        (reset! trampoline-arena arena)
        (reset! trampoline-callable callable))))

(defn- ensure-interp!
  "Create the interpreter once (libtcl loaded). Returns it."
  []
  (or @interp
      (do
        (let [[abi _] (load-lib "Tcl" tcl-libs abi-order)]
          (reset! tcl-abi abi))
        (tcl-find-executable "kmet")
        (let [i (tcl-create-interp)
              rc (tcl-init i)]
          (when-not (= rc tcl-ok)
            (throw (ex-info (str "kmet.gui.tcl: Tcl_Init failed: "
                                 (tcl-result i))
                            {:type :kmet.gui.tcl/init-failed})))
          (reset! interp i)
          i))))

(defn- tcl-eval!
  "Evaluate SCRIPT, throwing with Tcl's result on error. BLOCKING? marks
   the call as parking in Tcl's event loop (vwait)."
  [script blocking?]
  (let [i (ensure-interp!)
        rc (if blocking?
             #?(:bb (eval-script i script)
                :jolt (let [p (ffi/string->ptr script)]
                        (try
                          (tcl-eval-ex-blocking i p tcl-index-none 0)
                          (finally
                            (ffi/free p)))))
             (eval-script i script))]
    (when-not (= rc tcl-ok)
      (throw (ex-info (str "kmet.gui.tcl: " (tcl-result i))
                      {:type :kmet.gui.tcl/eval-failed :script script})))
    (or (tcl-result i) "")))

(defn- command!
  "Register NAME's HANDLER (a fn of the argv vector -> string)."
  [name handler]
  (let [i (ensure-interp!)
        _ (swap! handlers assoc name handler)
        cb (ensure-trampoline!)]
    (tcl-create-obj-command i name cb ffi/null ffi/null)
    nil))

(defn- prepare!
  "Load Tk into the interpreter (needs a display). Tk must match the
   interpreter's Tcl ABI, so the Tcl-loaded ABI is tried first.

   The Tk DLL is looked up on PATH and loaded through Tcl's `load`, with
   its appended zipfs library mounted first, so a standalone Tk DLL works
   from PATH with no compiled-in install prefix. When the path cannot be
   resolved, the FFI-preloaded DLL is left to Tk_Init's own install-path
   search (a system installation)."
  []
  (when-not @tk-ready?
    (let [i (ensure-interp!)
          abi @tcl-abi
          abis (if (contains? tk-libs abi)
                 (into [abi] (remove #(= % abi) abi-order))
                 abi-order)
          [_ tk-name] (load-lib "Tk" tk-libs abis)
          path (find-library tk-name)]
      (when-not (and path (= tcl-ok (eval-script i (load-tk-script path))))
        (let [rc (tk-init i)]
          (when-not (= rc tcl-ok)
            (throw (ex-info (str "kmet.gui.tcl: Tk_Init failed: " (tcl-result i))
                            {:type :kmet.gui.tcl/init-failed})))))
      (reset! tk-ready? true))))

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
  {:id :host-tcl
   :prepare! prepare!
   :eval (fn [script] (tcl-eval! script false))
   :command! command!
   :run-loop run-loop!})

(defn run!
  "Install the host FFI adapter and run the Tk backend on TREE, blocking
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
    (reset! tcl-abi nil)
    (reset! handlers {})
    (reset! trampoline-callable nil)
    (reset! trampoline-arena nil)))
