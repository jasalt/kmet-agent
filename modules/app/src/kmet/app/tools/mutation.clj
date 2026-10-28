(ns kmet.app.tools.mutation
  "Per-path serialization for file mutations.

   pi: file-mutation-queue.ts — two mutations of one file must not interleave
   even when their batch runs in parallel, while mutations of different files
   stay parallel. kmet also takes the lock for reads, so a read cannot observe
   a write's truncated half (pi does not; it is a cheap superset).

   The lock is in-process and keyed by the file's real path: a writer outside
   this process, and a script's own `spit`/shell write, are not tool calls and
   are beyond what a tool-level lock can order.

   Not re-entrant: a locked tool must not call another file tool."
  (:require [babashka.fs :as fs]))

(defonce ^:private tails
  ;; key -> promise delivered when the last section holding it finishes. A
  ;; defonce so a reload cannot empty the map while a section still holds a
  ;; lock, which would let the next caller of that path in alongside it.
  (atom {}))

(defn- lock-key
  "The lock key for PATH: the file's real path when it exists, so two
   spellings of one file (including a symlink) share it, else the normalized
   path (pi: getMutationQueueKey, which falls back the same way)."
  [path]
  (let [p (fs/normalize path)]
    (try
      (if (fs/exists? p) (str (fs/real-path p)) (str p))
      (catch Throwable _ (str p)))))

(def ^:private poll-ms
  "Slice a lock waiter sleeps before re-checking whether it should give up."
  25)

(defn- await-lock
  "Wait for PREV (the tail this caller queued behind) in slices, giving up
   when SIGNAL fires — a cancelled run must not keep a pool worker parked on
   another call's file write. SIGNAL nil never fires."
  [prev signal]
  (loop []
    (when (= ::pending (deref prev poll-ms ::pending))
      (when (and signal @signal)
        (throw (ex-info "Mutation cancelled while waiting for the file lock"
                        {:type :mutation/cancelled})))
      (recur))))

(defn with-file-lock
  "Run F with PATH's mutation lock held; returns F's value. A throw releases
   the lock and propagates. Concurrent callers for one path run one at a time
   in arrival order; callers for other paths are unaffected. SIGNAL is the
   run's cancel view: when it fires while waiting, the wait ends with a
   :mutation/cancelled ex-info instead of parking the caller."
  [path signal f]
  (let [key (lock-key path)
        tail (promise)
        [before] (swap-vals! tails assoc key tail)
        prev (get before key)]
    (try
      (when prev (await-lock prev signal))
      (f)
      (finally
        (deliver tail true)
        ;; drop only our own entry: a later section may already be waiting on
        ;; its own
        (swap! tails (fn [m] (if (identical? (get m key) tail) (dissoc m key) m)))))))
