(ns kmet.app.runtime
  "kmet2's boot and its submission point.

   `boot` turns a composed row list into a live ctx: one root, one fiber per
   row, mounted through `srce/plugin!` so a row's dependencies are declared
   rather than sequenced. It is the only place kmet2 mounts a plugin, and it
   fails loudly — a fiber that is not `:active` once the mounts settle is a
   composition bug (a name nothing provides, an `apply` that threw), not a run
   to start.

   The kernel is synchronous and single-threaded by rule (`srce.md` §8): plugin
   code runs on the thread that owns the root, and the one concurrency
   primitive is the pass flag. A thread that must change the kernel — a tool
   thread, a socket reader — therefore **submits**: `submit!` appends a thunk
   to the root's queue from any thread, and `boundary!` runs the queue and
   settles the kernel. That is the submission point; the turn boundary is
   where the loop calls `boundary!`.

   The queue lives in the root ctx's own map under `::submissions`, so a child
   ctx reaches it by `srce/attr` and nothing holds a pointer back to the
   root."
  (:require [clojure.string :as str]
            [kmet.srce.core :as srce]))

;; ── submissions ───────────────────────────────────────────────────────────

(def queue-key
  "Where a root ctx keeps its submission queue: one vector of thunks, drained
   by `boundary!`. A ctx key rather than a service, because a submission is
   process plumbing — it has no plugin, no lifetime and nothing to inject."
  ::submissions)

(defn- queue
  "The submission queue visible from CTX, or nil when CTX is not a booted
   kmet2 runtime."
  [ctx]
  (srce/attr ctx queue-key))

(defn submitting?
  "Can CTX take a submission (is it inside a booted runtime)?"
  [ctx]
  (some? (queue ctx)))

(defn submit!
  "Queue THUNK for the next boundary, from any thread. One append, so
   concurrent submitters are safe; nothing runs here. Throws
   :kmet.app/no-submissions outside a booted runtime — silently dropping a
   change is the failure mode this exists to prevent."
  [ctx thunk]
  (if-let [q (queue ctx)]
    (do (swap! q conj thunk) nil)
    (throw (ex-info "ctx is not a booted kmet2 runtime: no submission queue"
                    {:type :kmet.app/no-submissions}))))

(defn- take-submissions!
  "Atomically drain the queue. A concurrent append during the drain lands in
   the next round (an append is safe; the boundary re-checks)."
  [q]
  (loop []
    (let [v @q]
      (if (compare-and-set! q v [])
        v
        (recur)))))

(defn drain!
  "Run every queued thunk in submission order; returns how many ran. A
   throwing thunk is logged and does not stop the rest — the queue carries
   independent submissions, not a transaction."
  [ctx]
  (let [thunks (take-submissions! (queue ctx))]
    (doseq [thunk thunks]
      (try
        (thunk)
        (catch Throwable t
          (srce/log! ctx :error "submitted change threw" t))))
    (count thunks)))

(def max-rounds
  "The boundary's round budget: a submitted change may submit another, so the
   drain repeats until the queue is empty. A queue that has not drained after
   eight rounds is a churn — report it and return, exactly as the kernel's
   refresh pass does."
  8)

(defn boundary!
  "The turn boundary: run what other threads submitted, then settle the
   kernel's provider changes, repeating while submissions arrive. Returns
   `{:applied n :rounds n}`. Idempotent, and cheap when nothing was
   submitted."
  [ctx]
  (loop [round 0 applied 0]
    (let [n (drain! ctx)]
      (srce/settle! ctx)
      (cond
        (and (seq @(queue ctx)) (< (inc round) max-rounds))
        (recur (inc round) (+ applied n))

        (seq @(queue ctx))
        (do
          (srce/log! ctx :warn ":kmet.app/churn" {:rounds (inc round)})
          {:applied (+ applied n) :rounds (inc round)})

        :else {:applied (+ applied n) :rounds (inc round)}))))

;; ── boot ──────────────────────────────────────────────────────────────────

(defn- row-source
  "The plugin source ROW names: a var symbol resolved off the classpath, a var,
   or a plugin map as it is. A keyword is an `:ext/…` id, which the extension
   host resolves once it lands (`new-app.md`, landing 4); boot refuses it rather
   than mounting nothing."
  [row]
  (let [source (:plugin row)]
    (cond
      (or (var? source) (map? source)) source

      (symbol? source)
      (try
        (or (requiring-resolve source)
            (throw (ex-info (str "unresolved plugin source: " source)
                            {:type :kmet.app/unresolved-source :row row})))
        (catch Throwable t
          (throw (ex-info (str "cannot load the plugin for row " (pr-str (:id row))
                               ": " (or (ex-message t) (str t)))
                          {:type :kmet.app/unresolved-source :row row} t))))

      :else
      (throw (ex-info (str "row " (pr-str (:id row)) " names an external source "
                           (pr-str source) " — the extension host resolves those")
                      {:type :kmet.app/external-source :row row})))))

(defn mount-rows!
  "Mount ROWS under CTX and return one fiber per row, in composition order.

   `boot` is this on a fresh root; a **preset** is this on an agent's child ctx
   (`kmet.app.presets`). The mount point, not the row shape, is what a profile
   and a preset differ in — one composition language, two places to compose.
   Nothing settles here: the caller does, and then asks `inactive-rows`."
  [ctx rows]
  (mapv (fn [row] (srce/plugin! ctx (row-source row) (:config row))) rows))

(defn inactive-rows
  "ROWS whose fiber is not `:active` after the mounts settled, as plain data:
   what a failed mount is reported in terms of."
  [rows fibers]
  (into []
        (keep (fn [[row fiber]]
                (let [{:keys [state error waiting-for]} @fiber]
                  (when-not (= :active state)
                    {:id (:id row) :state state :error error
                     :waiting-for (vec (sort waiting-for))}))))
        (map vector rows fibers)))

(defn- declaration-conflicts
  "Event names that two mounted fibers declare differently, as
   `[{:event :declarations [{:plugin :spec …} …]} …]`. A name may be re-declared
   (an app-wide vocabulary row and the plugin that later claims an event), but
   only with the same mode, domain and doc — anything else is two plugins
   disagreeing about one event."
  [fibers]
  (->> fibers
       (mapcat (fn [fiber]
                 (let [{:keys [name events]} @fiber]
                   (map (fn [[event spec]] [event name spec]) events))))
       (group-by first)
       (keep (fn [[event decls]]
               (when (> (count (set (map peek decls))) 1)
                 {:event event
                  :declarations (mapv (fn [[_ plugin spec]] {:plugin plugin :spec spec}) decls)})))
       vec))

(defn- check-mounts!
  "Throw when any of ROWS is not `:active` in FIBERS, or when two of
   ALL-FIBERS (the boot's plus the just-mounted) declare one event differently —
   the two rules a boot holds every mount to. `boot` and `mount-deferred!`
   share them, so a mode mounted after the boot fails the same way a world row
   does."
  [ctx rows fibers all-fibers diagnose?]
  (let [failed (inactive-rows rows fibers)]
    (when (and (seq failed) (not diagnose?))
      (doseq [f failed]
        (srce/log! ctx :error "plugin did not mount" f))
      (throw (ex-info (str "kmet2 boot failed: " (str/join ", " (map (comp pr-str :id) failed))
                           " not active")
                      {:type :kmet.app/boot-failed :fibers failed}))))
  (let [conflicts (declaration-conflicts all-fibers)]
    (when (seq conflicts)
      (doseq [c conflicts]
        (srce/log! ctx :error "two plugins declare one event differently" c))
      (throw (ex-info (str "kmet2 boot failed: conflicting event declarations "
                           (str/join ", " (map (comp pr-str :event) conflicts)))
                      {:type :kmet.app/event-conflict :conflicts conflicts}))))
  true)

(defn boot
  "Mount COMPOSED's rows under a fresh root ctx and return `{:ctx :fibers
   :rows n :deferred […] :failed […]}`. Rows mount in composition order and
   settle once; every fiber must be `:active` afterwards, and no two fibers may
   declare one event differently, or boot throws `:kmet.app/boot-failed` naming
   the rows and their waiting-for, or the events they disagree about.

   `{:defer (fn [row])}` holds back the rows it matches: they are not mounted
   and come back in `:deferred` for `mount-deferred!`. The entry uses it for
   the mode row, whose `serve` starts the run: everything else mounts and
   settles first, the entry loads the process world into that tree, and only
   then does the mode mount.

   `{:diagnose? true}` returns the same map with the mounted-but-inactive rows
   in `:failed` instead of throwing: a diagnostic that cannot show the tree
   explaining the failure is useless exactly when it is needed, and the tree
   (`kmet.app.inspect`) is where a `:pending` row's `waiting-for` and a
   `:failed` row's error are. A boot that is meant to run keeps the hard
   failure — the two answers differ in the option, not in the mounting.

   The root is named `:app`, which is the first segment of every unit path the
   inspection renderer prints; CTX-META may name it differently (a root a test
   builds, a profile with a second tree)."
  ([composed] (boot composed nil))
  ([{:keys [rows]} {:keys [ctx-meta diagnose? defer]}]
   (let [ctx (srce/root (merge {queue-key (atom []) :name :app} ctx-meta))
         [deferred mounted] ((juxt filter remove) (or defer (constantly false)) rows)
         fibers (mount-rows! ctx mounted)
         _ (srce/settle! ctx)]
     (check-mounts! ctx mounted fibers fibers diagnose?)
     {:ctx ctx :fibers fibers :rows (count mounted)
      :deferred (vec deferred) :failed (inactive-rows mounted fibers)})))

(defn mount-deferred!
  "Mount the rows BOOT deferred, TRANSFORM applied to each first, and settle.
   Returns BOOT with the rows moved into `:fibers` (and `:deferred` empty), so
   a later `shutdown!` disposes them with the rest.

   The transform is where the entry merges the invocation into the mode's
   config — the values the world load had to install before the mode could be
   told them (a `--model` an extension provider names). Throws like `boot` when
   a row does not become active."
  [{:keys [ctx fibers deferred] :as booted} transform]
  (if (empty? deferred)
    booted
    (let [rows (mapv transform deferred)
          mounted (mount-rows! ctx rows)
          _ (srce/settle! ctx)
          all-fibers (into (vec fibers) mounted)]
      (check-mounts! ctx rows mounted all-fibers false)
      (assoc booted
             :fibers all-fibers
             :rows (+ (:rows booted) (count rows))
             :deferred []))))

(defn shutdown!
  "Dispose everything `boot` mounted, children first; the ctx is finished."
  [{:keys [ctx]}]
  (srce/dispose-subtree! ctx)
  nil)
