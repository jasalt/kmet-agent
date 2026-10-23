(ns kmet.tk.tcl
  "The host FFI Tcl/Tk adapter for kmet.tk.core (the \"hiccup-tk\" backend).

   Loads one matching Tcl/Tk pair — 9.1, 9.0 or 8.6, newest first —
   creates one interpreter, and exposes it to the portable backend
   through the adapter map:

     :prepare! (fn [] nil)              ; load libtk + Tk_Init (needs a display)
     :eval     (fn [script] -> string)  ; Tcl_Eval, throws on TCL_ERROR
     :command! (fn [name handler])      ; Tcl_CreateObjCommand over one
                                        ; callback trampoline
     :run-loop (fn [pump])              ; 25 ms after-timer + vwait

   Tk is a Tcl package, not a standalone toolkit: libtk imports libtcl
   and contributes its widget commands to the interpreter libtcl creates,
   so both libraries are loaded (libtk in `prepare!`, after the
   interpreter exists) from one Tcl/Tk installation — always as a pair,
   because the Tk major version must match Tcl's (Tk 8.6 links libtcl8.6,
   Tk 9.x links Tcl 9.x) and mixing them would put two Tcl runtimes in
   one process.

   `prepare!` resolves the Tk library in the install directories (PATH,
   the working directory, Termux's $PREFIX/lib — see `install-dirs`),
   mounts the zipfs library a Tcl 9 build appended to it, and loads it
   through Tcl's `load`; Tk_Init then finds tk.tcl with no compiled-in
   install prefix, so the DLLs are standalone. A system installation
   whose library Tk_Init finds through its own paths needs none of that:
   the name-level FFI preload is left to Tk_Init when the absolute path
   is not found (on Tcl 8.6 the zipfs mount is an invalid command, which
   the script's catch swallows).

   KMET_TCL_LIB and KMET_TK_LIB override the pair — both together, or a
   Tcl library whose Tk sibling is derived from its spelling.

   A failed Tcl resolution is memoized for the process, keyed on the
   search inputs (`search-key`): a machine without Tcl pays one sweep of
   the install directories, not one per probe. That matters because the
   sweep is not cheap — every candidate pair stats every PATH directory —
   and the test scaffolding probes once per live-Tk block.

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
            [kmet.tk.core :as tk])
  (:refer-clojure :exclude [run!]))

;; ─── Bindings ─────────────────────────────────────────────────────────────

;; Tcl 9 keeps Tcl_Eval / Tcl_GetString / Tcl_GetStringResult as macros over
;; these calls, so the adapter binds the real symbols. TCL_INDEX_NONE and
;; string lengths are a Tcl_Size (ptrdiff_t since Tcl 9), hence :int64.
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

(defn- install-dirs
  "The directories a shared library is looked for beyond the loader's own
   search paths: PATH entries in order, the process's working directory,
   and Termux's $PREFIX/lib, which its loader does not search."
  []
  (let [sep (re-pattern (java.util.regex.Pattern/quote
                         (System/getProperty "path.separator")))
        path (System/getenv "PATH")
        prefix (System/getenv "PREFIX")]
    (concat (when (seq path) (str/split path sep))
            [(System/getProperty "user.dir")]
            (when (seq prefix) [(str prefix "/lib")]))))

(defn- find-library
  "The absolute path of a shared library NAME found in the install
   directories, or nil. The Windows loader also searches the executable's
   directory, which PATH covers in kmet's deployments."
  [name]
  (some (fn [dir]
          (when (seq dir)
            (let [f (fs/path (str/replace dir #"^\"|\"$" "") name)]
              (when (fs/regular-file? f)
                (str (fs/absolutize f))))))
        (install-dirs)))

(defn- load-tk-script
  "A Tcl script that mounts PATH's appended zipfs library at
   //zipfs:/lib/tk and loads PATH as the Tk package. The mount makes the
   DLL standalone — Tk_Init finds tk.tcl in its own zip, with no
   compiled-in install prefix; `zipfs` is an invalid command when Tcl was
   built without zipfs, which the catch swallows."
  [path]
  (str "catch {zipfs mount " (tk/tcl-quote path) " //zipfs:/lib/tk}\n"
       "load " (tk/tcl-quote path) " Tk"))

(def ^:private library-pairs
  "Tcl/Tk shared-library candidate PAIRS, newest first, by platform. The
   pair is chosen as a unit: libtk links its own libtcl (Tk 8.6 pulls in
   libtcl8.6, Tk 9.x pulls Tcl 9.x), so trying versions independently
   could load two Tcl runtimes into one process, both exporting
   Tcl_CreateInterp. The first Tcl name that loads wins and only its
   pair's Tk names are then tried.

   Tcl 9 textually prefixes its Windows DLLs with the size (tcl91.dll);
   Tk 9's Windows build names the DLL tcl9tk<ver>.dll beside the plain
   tk<ver>.dll; Tcl/Tk 8.6 Windows builds use the t suffix."
  [;; Tcl 9.1
   ["libtcl9.1.so"    ["libtk9.1.so"]]
   ["libtcl9.1.dylib" ["libtk9.1.dylib"]]
   ["libtcl9.1.dll"   ["libtk9.1.dll"]]
   ["libtcl91.dll"    ["libtk91.dll"]]
   ["tcl91.dll"       ["tcl9tk91.dll" "tk91.dll"]]
   ["tcl91t.dll"      ["tk91t.dll"]]
   ;; Tcl 9.0
   ["libtcl9.0.so"    ["libtk9.0.so"]]
   ["libtcl9.0.dylib" ["libtk9.0.dylib"]]
   ["libtcl9.0.dll"   ["libtk9.0.dll"]]
   ["libtcl90.dll"    ["libtk90.dll"]]
   ["tcl90.dll"       ["tcl9tk90.dll" "tk90.dll"]]
   ["tcl90t.dll"      ["tk90t.dll"]]
   ;; Tcl 8.6
   ["libtcl8.6.so"    ["libtk8.6.so"]]
   ["libtcl8.6.dylib" ["libtk8.6.dylib"]]
   ["libtcl8.6.dll"   ["libtk8.6.dll"]]
   ["libtcl86.dll"    ["libtk86.dll"]]
   ["tcl86t.dll"      ["tk86t.dll"]]
   ["tcl86.dll"       ["tk86.dll"]]])

(defn- load-options
  "The values to hand ffi/load-library for library NAME: NAME itself
   first (the loader's own search paths), then the absolute path for
   every install directory that holds it."
  [name]
  (into [name]
        (keep (fn [dir]
                (when (seq dir)
                  (let [f (fs/path (str/replace dir #"^\"|\"$" "") name)]
                    (when (fs/regular-file? f)
                      (str (fs/absolutize f))))))
              (install-dirs))))

(defn- try-load
  "The first of NAMES whose shared library loads, or nil. Each name is
   tried bare and then by install-directory path (see `load-options`)."
  [names]
  (some (fn [n]
          (when (some (fn [option]
                        (try
                          (ffi/load-library option)
                          true
                          (catch Throwable _ nil)))
                      (load-options n))
            n))
        names))

(defn- derived-tk-names
  "The Tk library candidates derived from the spelling of the Tcl library
   at PATH: libtcl<ver> -> libtk<ver>, tcl<ver> -> tk<ver> and Tk 9's
   tcl9tk<ver> Windows spelling, as siblings when PATH names a directory."
  [path]
  (let [dir (fs/parent path)
        base (str (fs/file-name path))
        names (cond
                (str/starts-with? base "libtcl") [(str "libtk" (subs base 6))]
                (str/starts-with? base "tcl") (cond-> [(str "tk" (subs base 3))]
                                                (re-matches #"tcl9\d.*" base)
                                                (conj (str "tcl9tk" (subs base 3))))
                :else [])]
    (mapv (fn [n] (if dir (str (fs/path dir n)) n)) names)))

(defn- override-pair
  "The KMET_TCL_LIB/KMET_TK_LIB candidate pair, or nil when neither is
   set. A Tcl override alone pairs with the Tk names derived from its
   spelling; a Tk override alone is an error — the pair is loaded as a
   unit, so there is no Tcl to pair it with."
  []
  (let [tcl (System/getenv "KMET_TCL_LIB")
        tk (System/getenv "KMET_TK_LIB")]
    (cond
      (and tcl tk) [[tcl [tk]]]
      tcl [[tcl (derived-tk-names tcl)]]
      tk (throw (ex-info (str "kmet.tk.tcl: KMET_TK_LIB requires "
                              "KMET_TCL_LIB (the Tcl/Tk pair is loaded as a unit)")
                         {:type :kmet.tk.tcl/bad-override}))
      :else nil)))

;; ─── Interpreter state ────────────────────────────────────────────────────

(defonce ^:private interp (atom nil))
(defonce ^:private handlers (atom {}))

;; {:key (search-key) :tried [names]} from the last failed resolution — a
;; miss is an installation fact, not interpreter state, so `stop!` leaves it
;; in place. Cleared when a search succeeds under a new key.
(defonce ^:private tcl-missing (atom nil))

;; The loaded pair: {:tcl name :tk-candidates [names]} at load time, plus
;; :tk once the Tk library loads.
(defonce ^:private libraries (atom nil))

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

(defn- search-key
  "The inputs a library search reads, as one comparable value: a memoized
   miss is reused only while they are unchanged, so a caller that changes
   the working directory or the override variables searches again."
  []
  [(System/getenv "PATH")
   (System/getenv "PREFIX")
   (System/getProperty "user.dir")
   (System/getenv "KMET_TCL_LIB")
   (System/getenv "KMET_TK_LIB")])

(defn- missing-library
  "The no-Tcl error, naming the candidate library NAMES that were tried."
  [tried]
  (ex-info (str "kmet.tk.tcl: no Tcl 9.1/9.0/8.6 shared library "
                "could be loaded — install Tcl/Tk, or set "
                "KMET_TCL_LIB and KMET_TK_LIB")
           {:type :kmet.tk.tcl/missing-library
            :tried tried}))

(defn- load-tcl!
  "Load the newest candidate Tcl pair that loads and record it, the
   overrides first. Returns the pair. The choice is a unit: once a Tcl is
   loaded, its matching Tk is the only Tk that may be.

   A miss is memoized (see `tcl-missing`): the sweep stats every install
   directory for every candidate pair, and a caller that probes repeatedly
   would pay it every time."
  []
  (let [key (search-key)]
    (when-let [{cached :key tried :tried} @tcl-missing]
      (when (= key cached)
        (throw (missing-library tried))))
    (let [pairs (or (override-pair) library-pairs)]
      (or (some (fn [[tcl tk :as pair]]
                  (when (try-load [tcl])
                    (reset! libraries {:tcl tcl :tk-candidates tk})
                    (reset! tcl-missing nil)
                    pair))
                pairs)
          (let [tried (mapv first pairs)]
            (reset! tcl-missing {:key key :tried tried})
            (throw (missing-library tried)))))))

(defn- load-tk!
  "Load the Tk library paired with the recorded Tcl and record its name.
   Only the pair's candidates are tried: Tk must match the Tcl major."
  []
  (let [{:keys [tcl tk-candidates]} @libraries
        tk (try-load tk-candidates)]
    (if (some? tk)
      (do (swap! libraries assoc :tk tk) tk)
      (throw (ex-info (str "kmet.tk.tcl: Tcl loaded (" tcl ") but no matching "
                           "Tk library could be loaded — install the matching "
                           "Tk, or set KMET_TCL_LIB and KMET_TK_LIB")
                      {:type :kmet.tk.tcl/missing-library
                       :tcl tcl
                       :tried tk-candidates})))))

(defn- ensure-interp!
  "Create the interpreter once (a Tcl library loaded). Returns it."
  []
  (or @interp
      (do
        (load-tcl!)
        (tcl-find-executable "kmet")
        (let [i (tcl-create-interp)
              rc (tcl-init i)]
          (when-not (= rc tcl-ok)
            (throw (ex-info (str "kmet.tk.tcl: Tcl_Init failed: "
                                 (tcl-result i))
                            {:type :kmet.tk.tcl/init-failed})))
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
      (throw (ex-info (str "kmet.tk.tcl: " (tcl-result i))
                      {:type :kmet.tk.tcl/eval-failed :script script})))
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
  "Load Tk into the interpreter (needs a display).

   The Tk library paired with the loaded Tcl is resolved in the install
   directories and loaded through Tcl's `load`, with the zipfs library a
   Tcl 9 build appended to it mounted first, so a standalone Tk DLL works
   with no compiled-in install prefix (on Tcl 8.6 the mount is a caught
   no-op). When the path cannot be resolved, the FFI-preloaded library is
   left to Tk_Init's own install-path search (a system installation)."
  []
  (when-not @tk-ready?
    (let [i (ensure-interp!)
          tk-name (load-tk!)
          path (find-library tk-name)]
      (when-not (and path (= tcl-ok (eval-script i (load-tk-script path))))
        (let [rc (tk-init i)]
          (when-not (= rc tcl-ok)
            (throw (ex-info (str "kmet.tk.tcl: Tk_Init failed: " (tcl-result i))
                            {:type :kmet.tk.tcl/init-failed})))))
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
  "The adapter map for kmet.tk.core."
  []
  {:id :host-tcl
   :prepare! prepare!
   :eval (fn [script] (tcl-eval! script false))
   :command! command!
   :run-loop run-loop!})

(defn install!
  "Install the host FFI adapter (interpreter + Tk) without mounting, for a
   caller that scripts Tk itself (kmet.tk.ttk.style) or mounts with its
   own tag table (kmet.gui-sample.ui/backend through kmet.tk.core/mount-backend!
   and run-backend!). Registers the `kmet::cb` trampoline too, so a caller
   that scripts Tk before mounting — a shared variable's trace, a style
   callback — has it. mount! registers it again for adapters bound
   without install!."
  []
  (ensure-interp!)
  (let [a (adapter)]
    (tk/set-adapter! a)
    ((:prepare! a))
    ((:command! a) "kmet::cb" tk/dispatch-callback!))
  nil)

(defn mount!
  "REPL entry point: install the host FFI adapter (interpreter + Tk), mount
   TREE in a live window and return the handle (see kmet.tk.core/mount!).

   Development loop: mount on the thread that will own the interpreter, run
   kmet.tk.core/run-loop! there, then edit a component namespace, reload it
   and kmet.tk.core/render! to repaint with the new code."
  [tree & [options]]
  (install!)
  (tk/mount! tree options))

(defn run!
  "Install the host FFI adapter and run the Tk backend on TREE, blocking
   until the window closes. OPTIONS are the :toplevel tag's props
   (:title :width :height :bg :bind)."
  [tree & [options]]
  (install!)
  (tk/run! tree options))

(defn stop!
  "Delete the interpreter (tests, orderly shutdown). A memoized failed
   library search survives: it records an installation fact, not
   interpreter state (`stop!` runs in every live-Tk test's teardown)."
  []
  (when-some [i @interp]
    (tcl-delete-interp i))
  (reset! interp nil)
  (reset! tk-ready? false)
  (reset! handlers {})
  (reset! trampoline-callable nil)
  (reset! trampoline-arena nil)
  (reset! libraries nil))
