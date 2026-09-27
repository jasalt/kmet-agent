(ns kmet.app.ui.dock
  "The editor dock: a stack of panels rendered in front of the editor
   (pi: interactive-mode showSelector + editorContainer).

   DOCK-STACK on CoreState holds entries
   {:component c :focus-target f :borrowed? b}; the top one renders and
   the entries below stay mounted but hidden, so removing what covers a
   surface reveals it again — the same shape as the overlay stack. The
   editor itself is not an entry: make-dock-area falls back to
   CURRENT-EDITOR-ATOM when the stack is empty.

   Entering:
   - mount! replaces the top (pi showSelector + disposeActiveSelector):
     the displaced top leaves the stack and is disposed unless it was
     mounted :borrowed? (the login dialog, an extension dialog the
     registry owns). Entries below it stay, so the surface a selector was
     opened from is revealed again when the selector closes.
   - cover! pushes without touching anything below (pi showAuthSelect's
     temporary editorContainer swap): the login dialog's method selector
     covers it, and closing the selector reveals the dialog.

   Leaving is by membership, never by a mount token:
   - the done fn returned by a mount removes its component from wherever
     it sits in the stack; a handle whose component is gone is inert —
     the replacement semantics pi gets from clearing editorContainer.
   - release! is that same removal for owners that never kept the handle
     (the auth flow's dialog), and it works from any depth.
   - clear! empties the stack wholesale (a custom-editor swap, a session
     reset, an extension teardown), disposing every non-borrowed entry.

   Disposal stays with the owner: removals never dispose, because a
   docked panel is often composed of several owners' parts (a compiled
   frame plus a spliced foreign list) and only the owner knows the parts.
   dispose! is the owner's ordered close for a panel: it guarantees the
   component left the stack first (see its docstring) so the dock can
   never keep rendering a corpse — the dead-on-screen class of issue #5.

   Focus: the ::focus-guard watch on DOCK-STACK is the chokepoint. When
   the focused component leaves the stack it hands input to the new top's
   :focus-target, else to the resolver's fallback (the focus home: the
   top dock target or the active editor). mount!/cover! take focus
   explicitly before publishing the stack — only they know the new
   panel's target (a selector's inner list, not its chrome).

   The lifecycle contract and the refactor record live in dock.md."
  (:require [kmet.app.ui.custom-dialog-adapter :as cda]
            [kmet.debug :as debug]
            [kmet.libs.reakt :as r]
            [kmet.tui.core :as tui]))

(defn- entry
  [component focus-target borrowed?]
  {:component component
   :focus-target (or focus-target component)
   :borrowed? (boolean borrowed?)})

(defn- update-stack!
  "Atomically replace CS's dock stack with (f stack). Returns
   {:old old :new new} — the watcher has already seen :new by the time
   this returns, so callers use the pair for focus and disposal. A CS
   without a dock (minimal test states) is treated as an empty one."
  [cs f]
  (if-let [dock (:dock-stack cs)]
    (loop []
      (let [old @dock
            new (f old)]
        (if (compare-and-set! dock old new)
          {:old old :new new}
          (recur))))
    {:old nil :new nil}))

(defn- remove-component
  "Take every entry whose :component is COMPONENT (identity) out of STACK.
   Returns {:removed entries :remaining stack}."
  [stack component]
  (let [remove? #(identical? component (:component %))]
    {:removed (filterv remove? stack)
     :remaining (into [] (remove remove?) stack)}))

(defn- stack-has?
  "True when C is an entry's component or focus target in STACK."
  [stack c]
  (boolean (some (fn [e]
                   (or (identical? c (:component e))
                       (identical? c (:focus-target e))))
                 stack)))

(defn- top-entry [stack] (peek stack))

(defn top-component
  "The component the dock currently shows (the stack's top), or nil when
   the editor is all there is."
  [stack]
  (:component (top-entry stack)))

(defn top-focus-target
  "The component that should receive input while the dock's top renders —
   a selector's inner list, or the panel itself when it was mounted
   without an explicit focus target. Nil when the stack is empty."
  [stack]
  (:focus-target (top-entry stack)))

(defn registered?
  "True when COMPONENT is still in CS's dock stack (identity). Tolerates a
   CS without a dock (minimal test states). The dispose! invariant builds
   on this."
  [cs component]
  (let [stack (some-> (:dock-stack cs) deref)]
    (boolean (some #(identical? component (:component %)) stack))))

(defn- install-focus-guard!
  "Ensure the ::focus-guard watch on DOCK-STACK. The dock is the owner of
   the editor slot, so it is also the chokepoint that decides what happens
   to input when a panel leaves it (pi: disposeActiveSelector's restore).
   The watch — not each close path — makes the invariant hold: whenever
   the component holding focus was in the old stack and is gone from the
   new one, input goes to the new top's focus target, else to the
   resolver's fallback. That covers a top removal revealing a covered
   surface, a removal from any depth, clear!, a bare atom reset and any
   future path. Same shape as the TUI's ::ghost-guard on the overlay
   stack: a watch cannot be bypassed by construction. Idempotent (a fixed
   watch key), never throws (it runs inside swap! on the input dispatch
   path).

   Taking focus does NOT happen here: a mount knows the new panel's focus
   target (a selector's inner list), which no watch could guess, so
   mount!/cover! call tui-set-focus before publishing."
  [cs]
  (when-let [dock (:dock-stack cs)]
    (add-watch dock ::focus-guard
               (fn [_ _ old new]
                 (try
                   (let [tui* (:tui cs)
                         focused (when (some? tui*) (tui/tui-focused-component tui*))
                         old (or old [])
                         new (or new [])]
                     (when (and (some? focused)
                                (stack-has? old focused)
                                (not (stack-has? new focused)))
                       (if-let [top (top-entry new)]
                         ;; a covered surface is revealed: input goes to its
                         ;; focus target
                         (tui/tui-set-focus tui* (:focus-target top))
                         ;; the dock emptied: the resolver's fallback
                         ;; (topmost capturing overlay, else the focus home)
                         (tui/tui-release-focus! tui* focused))))
                   (catch Throwable _ nil)))))
  nil)

(defn make-dock-area
  "The editor dock as a fn component (dsl.md stage 4, pi: the editorDock
   container): renders the stack's top component if any, else the active
   editor from CURRENT-EDITOR-ATOM (the default or a swapped-in custom
   editor). Both reads are tracked, so stack pushes/pops and custom-editor
   swaps re-derive the tree exactly once; records splice foreign —
   reconcile swaps identity, disposes nothing. Panel lifecycles belong to
   the dock ops (displacement, clear!) and to the panel's owner (its own
   close)."
  [dock-stack current-editor-atom]
  (fn [_props]
    (or (top-component (r/tracked-deref dock-stack))
        (r/tracked-deref current-editor-atom))))

(defn- describe-component
  [component]
  (str (type component)))

(declare release!)

(defn dispose!
  "Dispose COMPONENT — the owner's ordered close for a panel it mounted.
   The invariant (dock.md): a component is disposed only after it left the
   stack, or the dock keeps rendering a corpse whose keys reach nothing
   (issue #5). A still-registered component is a lifecycle bug: remove it
   first so the visible failure cannot happen, record the violation in
   kmet.error.log, and with --debug throw so development trips on it
   instead of shipping another stranded panel. Handles nil."
  [cs component]
  (when (some? component)
    (let [was-docked? (registered? cs component)]
      (when was-docked?
        (release! cs component)
        (debug/log-error
         "disposed a component that was still in the editor dock (removed first):"
         (describe-component component)))
      (cda/dispose-component! component)
      (when (and was-docked? (debug/enabled?))
        (throw (ex-info "Component disposed while still in the editor dock"
                        {:type :dock/disposed-while-docked
                         :component (describe-component component)}))))))

(defn mount!
  "Swap COMPONENT in as the dock's top: replace and dispose the displaced
   top unless it was borrowed, keep the entries below (pi: showSelector +
   disposeActiveSelector). FOCUS-TARGET (pi: showSelector's `focus`) is
   the component that receives keys — the interactive child when COMPONENT
   itself is inert chrome; defaults to COMPONENT. Returns DONE: a zero-arg
   fn removing COMPONENT from the stack by identity, inert once it is
   gone (pi's token check, expressed as membership). Pass :borrowed? true
   for a panel whose owner keeps custody and re-mounts or disposes it (the
   auth dialog, an extension dialog); the dock then never disposes it.

   Focus is taken explicitly before the stack is published because only
   the caller knows the target; the ::focus-guard watch (installed here)
   handles every close path."

  ([cs component]
   (mount! cs component nil {}))
  ([cs component focus-target]
   (mount! cs component focus-target {}))
  ([cs component focus-target {:keys [borrowed?]}]
   (install-focus-guard! cs)
   (let [e (entry component focus-target borrowed?)]
     (tui/tui-set-focus (:tui cs) (:focus-target e))
     (let [{:keys [old]} (update-stack! cs #(conj (vec (butlast %)) e))
           displaced (top-entry old)]
       (when (and displaced
                  (not (identical? (:component displaced) component))
                  (not (:borrowed? displaced)))
         (dispose! cs (:component displaced)))
       (tui/tui-request-render (:tui cs))
       (fn done [] (release! cs component))))))

(defn cover!
  "Push COMPONENT on top of the dock without touching what is below (pi:
   showAuthSelect swaps the login dialog out and back; a temporary surface
   covers its owner). FOCUS-TARGET and :borrowed? as in mount!. Returns
   DONE, which reveals the covered surface again. Pushing a component
   that is already in the stack adds another entry; release!/done remove
   every entry that holds it."
  ([cs component]
   (cover! cs component nil {}))
  ([cs component focus-target]
   (cover! cs component focus-target {}))
  ([cs component focus-target {:keys [borrowed?]}]
   (install-focus-guard! cs)
   (let [e (entry component focus-target borrowed?)]
     (tui/tui-set-focus (:tui cs) (:focus-target e))
     (update-stack! cs #(conj % e))
     (tui/tui-request-render (:tui cs))
     (fn done [] (release! cs component)))))

(defn release!
  "Owner-callable leave: remove COMPONENT from CS's dock stack, wherever
   it sits (identity, not a mount generation). Returns true when it
   removed anything. The done fn returned by mount!/cover! is this
   operation; use release! when the owner never kept the handle, e.g. a
   flow that mounted its dialog before a selector covered it. A component
   that is no longer in the stack is inert, so a stale owner cannot yank a
   newer occupant.

   Removals never dispose — the owner does (see dispose!). Focus needs no
   restoring here: the ::focus-guard watch hands input to the revealed
   surface, or to the focus home."
  [cs component]
  (install-focus-guard! cs)
  (let [{:keys [old new]} (update-stack! cs #(:remaining (remove-component % component)))]
    (when (> (count old) (count new))
      (tui/tui-request-render (:tui cs))
      true)))

(defn clear!
  "Take the editor dock back wholesale (pi: disposeActiveSelector +
   editorContainer.clear()): empty the stack, disposing every non-borrowed
   entry and leaving borrowed ones to their owner. Use when something that
   is not itself a dock mount takes the editor slot — a custom-editor
   swap, a session reset, an extension teardown.

   Focus needs no explicit restore: the ::focus-guard watch sees the
   focused occupant leave and hands input to the resolver's fallback —
   including for a caller that never mounted anything and so never
   installed the guard (clear! installs it)."
  [cs]
  (install-focus-guard! cs)
  (let [{:keys [old]} (update-stack! cs (constantly []))]
    (doseq [e old :when (not (:borrowed? e))]
      (dispose! cs (:component e)))))
