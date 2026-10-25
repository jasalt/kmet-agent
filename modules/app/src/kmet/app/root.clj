(ns kmet.app.root
  "The process root: the ctx an entry mounts once and every kmet1 shim, the
   extension host's ctx-less reads and `/units` answer from.

   An atom rather than a delay because an entry that boots the composition
   itself (`--app new`) publishes *its* root here, so a process has one tree —
   the one it mounted — instead of a shim booting a second one behind it. The
   first root wins; a test that publishes its own puts the previous one back
   (`reset-root!`/`restore-root!`) rather than clearing it, because the root carries the
   process's registrations.")

(defonce ^:private app-root (atom nil))

(defn publish!
  "Make CTX the process root — the ctx `ctx` returns and every kmet1 shim
   reads. Returns the root in place.

   A caller publishes **before** anything reads a shim: otherwise the first
   reader boots a second composition behind the one the entry mounted, and
   kmet1's process-level code (the façades, the extension host's ctx-less
   reads, `/units`) sees a tree that is not the one the run is using. The
   first root wins — a second publish returns the root already in place rather
   than stranding a tree things are already reading."
  [ctx]
  (if (compare-and-set! app-root nil {:ctx ctx})
    ctx
    (:ctx @app-root)))

(defn ctx
  "The published process root, or nil when the process has mounted none yet."
  []
  (:ctx @app-root))

(defn boot!
  "The process root, booting and publishing one when the process has none
   yet: BOOT! is called at most once, under the atom's lock, so two readers
   racing the first read mount one tree."
  [boot!]
  (or (ctx)
      (locking app-root
        (or (ctx) (publish! (boot!))))))

(defn reset-root!
  "Clear the process root and return what it was.

   A test seam, and it hands the old state back for a reason: the root is
   process-global and carries registrations, so a test that published its own
   must put the previous one back (`restore-root!`) — clearing it would make the
   next test boot a **second** composition that has none of the registrations
   the first one carried."
  []
  (let [prev @app-root]
    (reset! app-root nil)
    prev))

(defn restore-root!
  "Put back what `reset-root!` returned (nil clears it)."
  [prev]
  (reset! app-root prev))
