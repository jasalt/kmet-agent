(ns kmet.agent.app-runtime
  "The agent runtime: the app's root ctx.

   `ctx` is the root context: kmet1 boots kmet2's composition behind it, so the
   registries, the event bus's scope, the loop's stage map and the session log
   all live on the ctx. The extension host registers there too since 4b-ii-a
   (its hooks, themes, UI port, renderers and flags are registry entries), and
   kmet1's own store node is gone since 4e — a registration is a service entry,
   not a key in a per-process node. `/units` renders the ctx tree the root
   carries (`kmet.app.inspect`).

   The loop's two stages ride the root ctx under `:loop-stages` as plain data:
   the loop reads its stage map once per attempt, and a run that needs its own
   gets a `child-ctx` carrying its overrides (P7).

   Registrations are owned: what `init(api)` installs lands under
   `[:app :extensions <name>]` in the app's registries, so unloading an
   extension is one `deregister-owner!` per tag plus the fns it recorded for
   what lives outside any registry (and its fiber, when it declared `:inject`).

   One sharp edge comes with the root ctx, and the host owns it: it is a
   process global every front end, engine and loaded extension shares — a run
   that must not share registrations gets its own `child-ctx`."
  (:require [kmet.agent.debug :as debug]
            [kmet.agent.loop-guard :as loop-guard]
            [kmet.agent.registry :as reg]
            [kmet.agent.retry :as retry]
            [kmet.agent.tools.registry :as tools-registry]
            [kmet.app.core :as app-core]
            [kmet.app.runtime :as app-runtime]
            [kmet.srce.core :as srce]))

(def default-loop-stages
  "The loop's two stages, as the plain map the loop reads: each stage carries
   a `:call` function, and an optional `:config` (the loop-guard threshold, the
   retry policy's budget and backoff shape). A ctx may carry an override; a
   subagent's ctx will."
  {:loop-guard {:call loop-guard/loop-guard-filter}
   :retry-policy {:call retry/retry-decision}})

(defonce ^:private app-root
  ;; The stage map rides the root ctx under `:loop-stages`, so a child ctx
  ;; reaches it by `srce/attr` and a run that passes its own carries its own.
  (delay (app-runtime/boot (app-core/compose-config {:profile :tui})
                           {:ctx-meta {:loop-stages (atom default-loop-stages)}})))

(defn ctx
  "The app root ctx: the composed rows kmet1 registers into."
  []
  (:ctx @app-root))

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
   (let [meta {:loop-stages (atom (merge-with merge default-loop-stages stages))}]
     (if parent (srce/extend parent meta) (srce/root meta)))))

(def ^:private registry-tags
  "Every registry tag kmet1 registers under, and the service that owns it — nil
   for the tags with no seam yet (the extension host's own registrations:
   hooks, themes, the UI port, entry/message renderers, flags), which share the
   façade's `remainder` handle until they get services. `deregister-owner!`
   walks these tags, so a tag the host registers under and a tag it unloads
   under are the same list."
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

;; kmet1's tool call sites pass no handle — they predate the ctx-sourced
;; `:tools` service — so the tool layer resolves them through this cell, which
;; is the same façade every explicit call here uses: one handle, two ways in.
;; kmet2 installs nothing, and a reader there that forgets to pass the ctx's
;; handle gets a named error instead of kmet1's tools (new-app.md, 4c-iii-c
;; takes the arity-less forms out).
(tools-registry/set-default-registry! registry)

(defn deregister-owner!
  "Remove every registry registration OWNER holds, across every tag — what
   unloading an extension or a resource contributor needs now that the
   registries are services rather than store entries (4c-ii): one call per tag
   instead of tracking a disposer per registration."
  [owner]
  (doseq [tag (keys registry-tags)]
    (reg/deregister-owner! (registry) tag owner))
  nil)

(defn loop-stages
  "CTX's loop stage map, read once per attempt. The nearest `:loop-stages` on
   the ctx chain wins — the root carries the builtin defaults, a child ctx its
   overrides — and a ctx that carries none (a bare test ctx) reads the
   defaults."
  [ctx]
  (if-some [stages (and ctx (srce/attr ctx :loop-stages))]
    @stages
    default-loop-stages))

(defn- own-stages
  "The stage atom CTX itself carries, or nil — no inheritance here, so a
   refresh names its target instead of reaching up the chain."
  [ctx]
  (when (and ctx (srce/ctx? ctx)) (:loop-stages ctx)))

(defn refresh-stages!
  "Re-seat the loop stages with the builtin defaults, so a reloaded guard or
   retry stage reaches the next attempt; an in-flight attempt keeps the map it
   read. It targets TARGET's own stage atom when it has one, and the app root's
   otherwise — never an ancestor's by inheritance: `loop-stages` inherits, a
   refresh names what it changes. `/reload` calls the no-arg form."
  ([] (refresh-stages! nil))
  ([target]
   (when-some [stages (or (own-stages target) (:loop-stages (ctx)))]
     (reset! stages default-loop-stages))))

(defn dispose-deregisters!
  "Run FNS — the deregister fns an extension recorded for what lives outside
   the app tree — in reverse registration order, each isolated and a failure
   logged instead of skipping the rest. Returns nil."
  [fns]
  (doseq [f (reverse fns)]
    (try (f) (catch Throwable t (debug/log-error "extension cleanup failed: " t))))
  nil)
