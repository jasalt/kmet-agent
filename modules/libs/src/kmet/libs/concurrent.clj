(ns kmet.libs.concurrent
  "Small concurrency primitives shared across layers:

   - `spawn` — the extension-SCI daemon-thread replacement for `future`
   - `or-signal` — a read-only OR-view over cancel signals (the provider
     stream's guard trip, the run_code bridge's abort), used wherever a
     consumer must poll two triggers as one
   - `queue`/`put!`/`take!`/`close!` — the hand-off between a blocking reader
     thread and the owner thread that consumes what it read

   Extensions run in isolated SCI contexts where `future`/`pmap`/`pcalls`
   are not available — SCI is a pure interpreter with no bundled
   Executor, while babashka injects `future` only into its host context
   (see `kmet.agent.extensions.core/build-context-namespaces`). `kmet` therefore
   exposes this tiny helper instead of whitelisting `future`:

   - host: `babashka` embeds `sci` + injects `future` via `:namespaces`
   - extensions: second `sci` context built by `kmet.agent.extensions.core` —
     only `slurp`/`spit`/`file-seq` from `clojure.core` plus the shared
     `kmet.tui.*` / `kmet.libs.*` layers are injected; `future` is
     deliberately omitted.

   Whitelisting `future` would pull in an implicit `Executor`, make
   extension work survive `unload-extension!`/`reload-extensions!`
   (non-daemon futures keep kmet alive), enable unbounded submission
   with no backpressure, and require the rest of the `future` family
   (`future-call`, `future-cancel`, …) plus `pmap`/`pcalls` for
   consistency. Keeping extensions on an explicit daemon `Thread` makes
   the lifecycle obvious and keeps unload/reload clean. If a nicer
   primitive is needed later, expose exactly this `spawn` — not `future`.")

(defn spawn
  "Start a daemon thread running `(f)` — the extension-SCI replacement
   for `future` (which is not available in extension contexts).

   Exceptions in `f` are logged via `kmet.agent.debug/log` and otherwise
   swallowed (extension background work must never kill the host).
   Returns the `Thread` (already started, daemon=true)
   so callers that need to `.interrupt` / `.join` it can."
  [f]
  (let [t (Thread. (fn [] (try (f) (catch Throwable e
                                     (try
                                       (requiring-resolve 'kmet.agent.debug/log)
                                       (when-let [log-fn (resolve 'kmet.agent.debug/log)]
                                         (log-fn "spawn failed" e))
                                       (catch Throwable _ nil))))))]
    (.setDaemon t true)
    (.start t)
    t))

(defn or-signal
  "A read-only derefable that is true when any of SIGNALS fires — the OR-view
   of a run's cancel atom and a local trigger. A nil signal never fires
   (a script run outside the loop's cancel plumbing). The view is deref-only:
   the underlying atoms stay the single source of truth, never reset or
   watched through it."
  [& signals]
  (reify clojure.lang.IDeref
    (deref [_] (boolean (some (fn [s] (when s (boolean @s))) signals)))))

(def closed
  "What `take!` answers once the queue is closed and drained. A keyword rather
   than nil because a reader's line can be nil at end of input: a consumer must
   be able to tell \"nothing more will come\" from \"an empty line\"."
  ::closed)

(defn- hand-out
  "One atomic take decision: the next item, the closed marker, or the signal to
   park on. Keeping the decision in one `swap!` is what makes a `put!` that races
   a taker impossible to lose."
  [{:keys [items closed?] :as state}]
  (cond
    (seq items) (assoc state :handed (peek items) :items (pop items))
    (true? closed?) (assoc state :handed closed)
    :else (update state :signal #(or % (promise)))))

(defn queue
  "A hand-off queue: any number of producers, **one consumer** (`take!`), and
   `close!` to end it.

   It exists for the crossing a blocking reader needs: a socket reader thread
   parks on `read-line` and hands each line to the owner thread, which is the
   only thread allowed to touch the agent. A queue rather than a callback keeps
   the owner a plain loop; an atom rather than a blocking Java collection keeps
   it usable on both hosts and in extension contexts.

   The state is one atom — `:items` (a `PersistentQueue`), `:closed?`, and a
   `:signal` promise a waiting taker parks on — so every take is a single
   deciding swap. `close!` leaves buffered items readable and then answers
   `closed`, so a consumer drains what it was sent before it learns why it
   stopped.

   One consumer is the contract, not a courtesy: `take!` records what it handed
   out in the queue's own state for the duration of a swap, so two takers would
   race that slot. Two consumers need two queues."
  []
  (atom {:items clojure.lang.PersistentQueue/EMPTY :closed? false :signal nil}))

(defn take!
  "The next value, blocking until one arrives; `closed` once the queue is closed
   and drained. Single consumer (see `queue`)."
  [q]
  (loop []
    (let [state (swap! q hand-out)]
      (if-let [handed (find state :handed)]
        (let [value (val handed)]
          (swap! q dissoc :handed)
          value)
        (do (deref (:signal state))
            (recur))))))

(defn put!
  "Hand V to the consumer. False when the queue is closed: the item is dropped
   and the caller learns it, rather than believing it was queued."
  [q v]
  (let [[old _] (swap-vals! q (fn [{:keys [closed?] :as state}]
                                (if closed?
                                  state
                                  (-> state
                                      (update :items conj v)
                                      (assoc :signal nil)))))]
    (if (:closed? old)
      false
      (do (when-let [signal (:signal old)] (deliver signal true))
          true))))

(defn close!
  "End the queue: buffered items stay readable, then `take!` answers `closed`, and
   later `put!`s return false. True the first time, false after."
  [q]
  (let [[old _] (swap-vals! q #(if (:closed? %) % (assoc % :closed? true)))]
    (if (:closed? old)
      false
      (do (when-let [signal (:signal old)] (deliver signal true))
          true))))

(defn monotonic-ms
  "Elapsed-time milliseconds — the only clock for durations and deadlines:
   timeouts, sleep slices, backoff, idle reaping, elapsed counters.

   `System/currentTimeMillis` steps (NTP corrections, Android/Termux radio
   time, VM resume), and a duration computed on a stepping clock is simply
   wrong: a forward step fires timeouts early (killing work that has just
   started) and lets backoff expire instantly, a backward step stalls
   deadlines — a TUI timer armed for 50ms can park for the step's length.

   The wall clock stays right where the value is an absolute time or is
   compared against externally-produced wall-clock values: token and JWT
   expiries, epoch ids/timestamps, file mtimes, persisted cross-process
   cache TTLs.

   `nanoTime` has no epoch and cannot step. It also excludes suspend/doze
   time (CLOCK_MONOTONIC), so a deadline does not count the time the host
   spent asleep — the trade-off Go timers, `deref` timeouts and core.async
   already make, and the safe direction: never kill work because the clock
   moved."
  []
  (quot (System/nanoTime) 1000000))
