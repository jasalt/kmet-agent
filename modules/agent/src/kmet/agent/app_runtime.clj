(ns kmet.agent.app-runtime
  "The agent runtime: the app's root ctx.

   `ctx` is the root context: kmet1 boots kmet2's composition behind it, so the
   registries, the event bus's scope, the loop's stage map and the session log
   all live on the ctx. The extension host registers there too since 4b-ii-a
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

   One sharp edge comes with the root ctx, and the host owns it: it is a
   process global every front end, engine and loaded extension shares — a run
   that must not share registrations gets its own `child-ctx`."
  (:require [kmet.app.loop.stages :as loop-stages]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.extensions.services :as extensions-services]
            [kmet.agent.registry :as reg]
            [kmet.app.core :as app-core]
            [kmet.app.root :as root]
            [kmet.app.runtime :as app-runtime]
            [kmet.srce.core :as srce]))

(defn- boot-root!
  "Boot kmet1's process root: the composition, with the loop's stage map on it.
   The fibers are dropped with the boot map — the root lives for the process."
  []
  (:ctx (app-runtime/boot (app-core/compose-config {:profile :tui})
                          {:ctx-meta {:loop-stages (atom loop-stages/defaults)}})))

(defn publish-root!
  "Make CTX the process root — the ctx `ctx` returns and every kmet1 shim
   reads. `kmet.app.root/publish!` owns the publication and its first-root
   rule; this name is kmet1's entry point onto it."
  [ctx]
  (root/publish! ctx))

(defn reset-root!
  "Clear the process root and return what it was (a test seam; see
   `kmet.app.root/reset-root!`)."
  []
  (root/reset-root!))

(defn restore-root!
  "Put back what `reset-root!` returned (nil clears it)."
  [prev]
  (root/restore-root! prev))

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
  "Every registry tag kmet1 registers under, and the service that owns it — nil
   for the tags with no seam yet (the extension host's own registrations:
   hooks, themes, the UI port, entry/message renderers, flags), which share the
   façade's `remainder` handle until they get services.

   This is the routing table `registry` builds its handle from; unloading an
   extension sweeps the same tags through two other lists (the host's own
   `host-tags` and the resources' `resource-tags`, since the host may not
   require this shim). They are separate files, so they can drift — keep them
   equal: `test-lifecycle` does (`test-unload-sweeps-every-tag-kmet1-routes`),
   because a forgotten tag leaks (`:kmet/tool-source` did)."
  {:kmet/tool :tools
   :kmet/tool-source :tools
   :kmet/command :commands
   :kmet/prompt :prompts
   :kmet/skill :skills
   :kmet/hook nil
   :kmet/theme nil
   :kmet/ui-port nil
   :kmet/entry-renderer nil
   :kmet/message-renderer nil
   :kmet/flag nil})

(defonce ^:private registry-handle
  (delay
    (let [remainder (reg/atom-registry)]
      (reg/router (fn [tag]
                    (if-let [service (registry-tags tag)]
                      (srce/get (ctx) service)
                      remainder))))))

(defn registry
  "The `Registry` handle kmet1 registers through: one façade that routes each
   tag to the service that owns it in the app ctx (`kmet.agent.registry/router`),
   so a command registered here is the `:commands` service kmet2 reads, and one
   that has no seam yet still has somewhere to go."
  []
  @registry-handle)

;; Readers with no ctx in hand (commands, prompts, skills) ask
;; `kmet.agent.registry/current`; this shim is the owner that answers, so the
;; publishing happens here, where the tags->service table lives.
(reg/publish-process-handle! (registry))

;; The extension host lives in `app` and may not require this shim, so this is
;; where the process hands it its handles: the registry above, the ctx below,
;; and the resource registries it would otherwise have to name. The thunk is
;; only stored here — nothing resolves, let alone boots, until the host reads
;; it — and re-binding is how a different owner (an agent's ctx) takes over.
(extensions/bind-services!
 (fn [] (extensions-services/host-services (registry) (ctx))))

(defn deregister-owner!
  "Remove every registry registration OWNER holds, across every tag — what
   unloading an extension or a resource contributor needs now that the
   registries are services rather than store entries (4c-ii): one call per tag
   instead of tracking a disposer per registration."
  [owner]
  (doseq [tag (keys registry-tags)]
    (reg/deregister-owner! (registry) tag owner))
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
