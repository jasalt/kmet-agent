(ns kmet.agent.app-runtime
  "The agent runtime: the app's root ctx, and the store node it replaced.

   `ctx` is the root context: kmet1 boots kmet2's composition behind it, so the
   registries, the event bus's scope, the loop's stage map and the session log
   all live on the ctx. The extension host registers there too since 4b-ii-a
   (its hooks, themes, UI port, renderers and flags are registry entries), so
   what is left of `node` is kmet1's own store — `registry_store`'s backend and
   `/units`' owner listing, both retired by 4d/4e.

   The loop's two stages ride the root ctx under `:loop-stages` as plain data:
   the loop reads its stage map once per attempt (scope.md §4), and a run that
   needs its own gets a `child-ctx` carrying its overrides (P7).

   Registrations are owned (scope.md §2): what `init(api)` installs lands under
   `[:app :extensions <name>]` in the app's registries, so unloading an
   extension is one `deregister-owner!` per tag plus the fns it recorded for
   what lives outside any registry (and its fiber, when it declared `:inject`).

   One sharp edge comes with the root ctx, and the host owns it: it is a
   process global every front end, engine and loaded extension shares — a run
   that must not share registrations gets its own `child-ctx`."
  (:require [clojure.string :as str]
            [kmet.agent.debug :as debug]
            [kmet.agent.loop-guard :as loop-guard]
            [kmet.agent.registry :as reg]
            [kmet.agent.retry :as retry]
            [kmet.app.core :as app-core]
            [kmet.app.runtime :as app-runtime]
            [kmet.libs.scope :as scope]
            [kmet.srce.core :as srce]))

(defn- with-reported-cleanups
  "Run F with the store's cleanup reporter bound to
   `kmet.agent.debug/log-error` (scope.md §3): a failing cleanup is reported
   and never skips the rest, and the report reaches the agent's error log."
  [f]
  (binding [scope/*error-reporter*
            (fn [t] (debug/log-error "store cleanup failed: " t))]
    (f)))

(def default-loop-stages
  "The loop's two stages, as the plain map the loop reads: each stage carries
   a `:call` function, and an optional `:config` (the loop-guard threshold, the
   retry policy's budget and backoff shape). A ctx may carry an override; a
   subagent's ctx will."
  {:loop-guard {:call loop-guard/loop-guard-filter}
   :retry-policy {:call retry/retry-decision}})

(defonce ^:private app-node
  ;; No host fields: the stage map moved to the root ctx at 4c-ii-b, and the
  ;; extension host stopped registering here at 4b-ii-a. What remains is
  ;; kmet1's store until 4e.
  (delay (scope/node nil)))

(defn node
  "The app's root store node — kmet1's remaining scope store: `registry_store`'s
  backend and `/units`' owner listing, retired at 4d/4e. Nothing the extension
  host installs lands here since 4b-ii-a."
  []
  @app-node)

;; ─── The app root context ──────────────────────────────────────────────────
;; kmet1 runs on kmet2's composition: the root ctx is the same one `--app new`
;; boots, mounted once per process and never shut down (process exit is the
;; disposal). The store node above is what is left of kmet1's own tree — the
;; extension host's owner paths and `/units` — and it retires after 4b-ii puts
;; the host on fibers (new-app.md § Landings).

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
   what `child-node` did before 4c-ii-b."
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

(def ^:private listed-registries
  "The service and tag each `/units` registry line shows, in display order."
  [[:commands :kmet/command]
   [:prompts :kmet/prompt]
   [:skills :kmet/skill]
   [:tools :kmet/tool]])

(defn registry-listing
  "One line per registry service that holds anything: `:commands keys=[…]`, read
   from the app ctx's handles. The registries left the store at 4c-ii, so the
   owner walk cannot see them any more; this is the same surface from the other
   side, and it is replaced by `kmet.app.inspect`'s tree at 4d."
  [ctx]
  (->> listed-registries
       (keep (fn [[service tag]]
               (when-let [handle (srce/get ctx service)]
                 (let [names (mapv first (reg/entries handle tag))]
                   (when (seq names)
                     (str (pr-str service) " keys=" (pr-str names)))))))
       (str/join "\n")))

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
   reported through the same reporter the store uses instead of skipping the
   rest (scope.md §3). Returns nil."
  [fns]
  (with-reported-cleanups
    #(doseq [f (reverse fns)]
       (try (f) (catch Throwable t (scope/*error-reporter* t)))))
  nil)

(defn- owner-line
  "One store owner as `/units` prints it: `<owner> keys=[…]`, the owner's
   registrations in registration order (scope.md §2, §6)."
  [owner registrations]
  (str (pr-str owner)
       " keys=" (pr-str (mapv first registrations))))

(defn owner-listing
  "Render the store owners that hold registrations for `/units`: one
   `<owner> keys=[…]` line each, in first-registration order, flat (scope.md
   §2, §6). An owner with only teardowns has nothing to show and is
   skipped."
  [node]
  (->> (scope/owners node)
       (keep (fn [owner]
               (let [registrations (scope/owner-registrations node owner)]
                 (when (seq registrations)
                   (owner-line owner registrations)))))
       (str/join "\n")))
