(ns kmet.agent.app-runtime
  "The agent runtime: one store node and the loop's stage map.

   `node` is the app root — the store (`kmet.libs.scope`) every front end,
   engine and loaded extension registers into, plus the loop's two stages as
   plain data. The loop reads its stage map once per attempt (scope.md §4),
   and a child node carries its own (P7).

   Registrations are owned (scope.md §2): what `init(api)` installs lands on
   `[:app :extensions <name>]`, so unloading an extension is one LIFO dispose
   of that owner plus the fns it recorded for what lives outside the tree.

   Two sharp edges come with the node, and the host owns both: `node` is a
   process global every front end, engine and loaded extension shares — a tree
   that must not share registrations makes its own `child-node`; and disposal
   never waits, so the host mutates the live store only where nothing is
   mid-run (`/reload`, bootstrap, a session swap)."
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
   retry policy's budget and backoff shape). A node may carry an override; a
   subagent's node will."
  {:loop-guard {:call loop-guard/loop-guard-filter}
   :retry-policy {:call retry/retry-decision}})

(defn- new-node
  "A store node whose stage map is the defaults merged with STAGES: a stage's
   `:call`/`:config` keys override the builtin entry, and the entry's other
   keys carry over. A non-nil PARENT also gets the node in its `:children`
   (`scope/node`), so `scope/dispose-node!` on the parent ends it (scope.md
   §5)."
  [parent stages]
  (scope/node parent
              {:loop-stages (atom (merge-with merge default-loop-stages stages))}))

(defn child-node
  "A fresh node carrying the loop stages — STAGES merged over the builtin
   defaults — linked to PARENT for subtree queries and tree disposal (scope.md
   §5). A run can point at one to get its own stage map (P7); a nil parent
   makes a root of its own."
  ([] (child-node nil))
  ([parent] (child-node parent nil))
  ([parent stages] (new-node parent stages)))

(defonce ^:private app-node
  (delay (child-node nil)))

(defn node
  "The app's root node. -main reaches this before extension loading, so every
   `init(api)` has an owner to register under (scope.md §6)."
  []
  @app-node)

;; ─── The app root context ──────────────────────────────────────────────────
;; kmet1 runs on kmet2's composition: the root ctx is the same one `--app new`
;; boots, mounted once per process and never shut down (process exit is the
;; disposal). The store node above is what is left of kmet1's own tree — the
;; extension host's owner paths, `/units` and the bus's scoping — and it retires
;; after 4b-ii puts the host on fibers (new-app.md § Landings).

(defonce ^:private app-root
  (delay (app-runtime/boot (app-core/compose-config {:profile :tui}))))

(defn ctx
  "The app root ctx: the composed rows kmet1 registers into."
  []
  (:ctx @app-root))

(def ^:private registry-tags
  "Every registry tag kmet1 registers under, and the service that owns it — nil
   for the tags with no seam yet (the extension host's UI-side registries:
   entry renderers, message renderers, flags), which share the façade's
   `remainder` handle until 4b-ii gives them services."
  {:kmet/tool :tools
   :kmet/tool-source :tools
   :kmet/command :commands
   :kmet/prompt :prompts
   :kmet/skill :skills
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
  "NODE's loop stage map, read once per attempt. A node that carries none (a
   bare store node) reads the builtin defaults."
  [node]
  (if-let [stages (:loop-stages node)]
    @stages
    default-loop-stages))

(defn refresh-stages!
  "Re-seat NODE's loop stages with the builtin defaults (the app root when no
   node is given), so a reloaded guard or retry stage reaches the next
   attempt; an in-flight attempt keeps the map it read. `/reload` does this."
  ([] (refresh-stages! (node)))
  ([node] (reset! (:loop-stages node) default-loop-stages)))

(defn dispose-owner!
  "Dispose OWNER's registrations on the app root, LIFO and isolated — an
   extension's own registrations when it unloads. Failures are reported, not
   swallowed (scope.md §3)."
  [owner]
  (with-reported-cleanups #(scope/dispose-owner! (node) owner)))

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
