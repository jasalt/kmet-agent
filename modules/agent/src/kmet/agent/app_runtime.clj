(ns kmet.agent.app-runtime
  "The agent runtime: the app's root ctx.

   `ctx` is the root context: kmet1 boots kmet2's composition behind it, and the
   publication and its first-root rule live in `kmet.app.root`. The registries,
   the event bus's scope, the loop's stage map and the session log all live on
   the ctx. The extension host registers there too since 4b-ii-a
   (its hooks, themes, UI port, renderers and flags are registry entries), and
   kmet1's own store node is gone since 4e — a registration is a service entry,
   not a key in a per-process node. `/units` renders the ctx tree the root
   carries (`kmet.app.inspect`).

   The loop's stage map lives in `kmet.app.loop.stages`: the loop reads it from
   its ctx, so the loop needs nothing of this shim. The root carries the builtin
   map under `:loop-stages`, and a run that needs its own gets a `child-ctx`
   carrying its overrides (P7).

   Registrations are owned: what `init(api)` installs lands under
   `[:app :extensions <name>]` in the app's registries, so unloading an
   extension is one `deregister-owner!` per tag plus the fns it recorded for
   what lives outside any registry (and its fiber, when it declared `:inject`).
   The handle is the composition's (`kmet.app.registries/process-handle`,
   published by its `:extensions` row); this shim reads it, it does not build a
   second one.

   One sharp edge comes with the root ctx, and the host owns it: it is a
   process global every front end, engine and loaded extension shares — a run
   that must not share registrations gets its own `child-ctx`."
  (:require [kmet.app.loop.stages :as loop-stages]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.registry :as reg]
            [kmet.app.core :as app-core]
            [kmet.app.registries :as registries]
            [kmet.app.root :as root]
            [kmet.app.runtime :as app-runtime]
            [kmet.srce.core :as srce]))

(defn- boot-root!
  "Boot kmet1's process root: the composition, with the loop's stage map on it.
   The fibers are dropped with the boot map — the root lives for the process."
  []
  (:ctx (app-runtime/boot (app-core/compose-config {:profile :tui})
                          {:ctx-meta {:loop-stages (atom loop-stages/defaults)}})))

(defn ctx
  "The app root ctx: the composed rows kmet1 registers into. The process
   publishes an entry's own composition here; a process without one boots
   kmet1's composition on the first read."
  []
  (root/boot! boot-root!))

(defn child-ctx
  "A ctx carrying the loop stages — STAGES merged over the builtin defaults —
   under PARENT, or as a root of its own when there is no parent. A run points
   at one to get its own stage map (P7): with a parent, what it registers lives
   in that ctx's subtree; without one it is its own isolation boundary, which is
   what `child-node` did before 4c-ii-b.

   This ctx has no fiber of its own, so nothing disposes it: the caller releases
   a ctx it built under a parent with `srce/detach!` when the run is done, or the
   tree keeps a line per run (`/units`)."
  ([] (child-ctx nil))
  ([parent] (child-ctx parent nil))
  ([parent stages]
   (let [meta {:loop-stages (atom (loop-stages/with-defaults stages))}]
     (if parent (srce/extend parent meta) (srce/root meta)))))

(def registry-tags
  "Every registry tag the process handle routes, and the service that owns it —
   nil for the tags with no service yet (the extension host's own
   registrations). `kmet.app.registries` owns the table now; this name is
   kmet1's entry point onto it, and unloading an extension sweeps the same tags
   through the host's two lists. They are separate files, so they can drift —
   keep them equal: `test-lifecycle` does (`test-unload-sweeps-every-tag-kmet1-
   routes`), because a forgotten tag leaks (`:kmet/tool-source` did)."
  registries/registry-tags)

(defn registry
  "The `Registry` handle kmet1 registers through: the process handle this
   composition published (`kmet.app.registries/process-handle`), so a command
   registered here is the `:commands` service kmet2 reads. A process that
   published nothing answers from the empty per-process fallback
   (`kmet.agent.registry/current`), which is not an error: a bare test or a
   cold start still has somewhere to write."
  []
  (reg/current))

;; The extension host lives in `app` and may not require this shim, so this is
;; where a kmet1 process hands it a binding before a composition exists: the
;; root's own row replaces it at mount, and this thunk must not serve the first
;; registration from the fallback handle — it mounts the root first, then reads
;; the binding again. Nothing resolves, let alone boots, until the host reads
;; it, and re-binding is how a different owner takes over.
(defonce ^:private shim-binding
  (fn []
    ;; Boot the root first: its `:extensions` row binds services (the process
    ;; handle, the resource operations), and the first registration must be
    ;; served from those, not from this fallback.
    (let [root (ctx)
          f (extensions/host-binding)]
      (if (identical? f shim-binding)
        (registries/host-services (reg/current) root)
        (f)))))

(extensions/bind-services! shim-binding)

(defn deregister-owner!
  "Remove every registry registration OWNER holds, across every tag — what
   unloading an extension or a resource contributor needs now that the
   registries are services rather than store entries (4c-ii): one call per tag
   instead of tracking a disposer per registration."
  [owner]
  (let [handle (registry)]
    (doseq [tag (keys registry-tags)]
      (reg/deregister-owner! handle tag owner)))
  nil)

(defn refresh-stages!
  "Re-seat the app root's loop stages with the builtin defaults
   (`kmet.app.loop.stages/refresh!`), so a reloaded guard or retry stage reaches
   the next attempt; an in-flight attempt keeps the map it read. `/reload` calls
   it: the process names the ctx a refresh targets, and a run that must be
   re-seated calls `kmet.app.loop.stages/refresh!` on its own."
  []
  (loop-stages/refresh! (ctx)))

(defn dispose-deregisters!
  "Kmet1's name for `kmet.agent.registry/dispose-deregisters!`: run FNS — the
   deregisters an extension recorded for what lives outside the app's
   registries — in reverse registration order. Returns nil."
  [fns]
  (reg/dispose-deregisters! fns))
