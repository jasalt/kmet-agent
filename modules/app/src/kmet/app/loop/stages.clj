(ns kmet.app.loop.stages
  "The loop's stage map: the repeat-loop guard and the retry decision, as plain
   data a run's ctx carries.

   The loop reads its stage map once per attempt (`for-ctx`), so a run that
   needs its own overrides gets a child ctx carrying them — the map rides the
   ctx chain under `:loop-stages`, which means nothing here knows which process,
   root or composition the ctx belongs to. A reload re-seats the map a ctx holds
   (`refresh!`) rather than reaching for one."
  (:require [kmet.app.loop-guard :as loop-guard]
            [kmet.app.retry :as retry]
            [kmet.srce.core :as srce]))

(def defaults
  "The loop's two stages, as the plain map the loop reads: each stage carries
   a `:call` function, and an optional `:config` (the loop-guard threshold, the
   retry policy's budget and backoff shape). A ctx may carry an override; a
   subagent's ctx will."
  {:loop-guard {:call loop-guard/loop-guard-filter}
   :retry-policy {:call retry/retry-decision}})

(defn with-defaults
  "OVERRIDES merged over the builtin defaults, stage by stage — what a ctx
   builder seats under `:loop-stages` when a run wants some of its own. A
   stage map a ctx carries is read whole (`for-ctx`), so this is where the
   defaults and the overrides meet."
  [overrides]
  (merge-with merge defaults overrides))

(defn for-ctx
  "CTX's loop stage map, read once per attempt. The nearest `:loop-stages` on
   the ctx chain wins — a root carries the builtin defaults, a child ctx its
   overrides — and a ctx that carries none (nil, a bare test ctx) reads the
   defaults."
  [ctx]
  (if-some [stage-atom (srce/attr ctx :loop-stages)]
    @stage-atom
    defaults))

(defn- own
  "The stage atom CTX itself carries, or nil — no inheritance here, so a
   refresh names its target instead of reaching up the chain."
  [ctx]
  (when (srce/ctx? ctx) (:loop-stages ctx)))

(defn refresh!
  "Re-seat CTX's own stage atom with the builtin defaults, so a reloaded guard
   or retry stage reaches the next attempt; an in-flight attempt keeps the map
   it read. `for-ctx` inherits, a refresh does not: a ctx that carries no stage
   atom of its own is left alone."
  [ctx]
  (when-some [stage-atom (own ctx)]
    (reset! stage-atom defaults)))
