(ns kmet.app.loop.stages
  "The loop's stage map: the repeat-loop guard and the retry decision, as plain
   data a run's ctx carries.

   The map has two sources, in this order. The **composition** provides the
   implementations — one row per stage (`:loop-guard`, `:retry-policy`), so a
   profile replaces the guard or the retry policy by naming another plugin under
   the same id — and `base` reads what is mounted. A **run** may seat its own
   map on its ctx (`:loop-stages`), which wins whole: `with-defaults` is where a
   builder merges, and `for-ctx` reads the map as-is.

   The loop reads the map once per attempt (`for-ctx`), so an override rides the
   ctx chain rather than reaching for a composition; a reload re-seats the map a
   ctx holds (`refresh!`)."
  (:require [kmet.app.loop-guard :as loop-guard]
            [kmet.app.retry :as retry]
            [kmet.srce.core :as srce]))

(def defaults
  "The loop's two stages: each carries a `:call` function and an optional
   `:config` (the guard's threshold, the retry policy's budget and backoff
   shape). The rows below provide these; a run may seat an override; a
   subagent's ctx will."
  {:loop-guard {:call loop-guard/loop-guard-filter}
   :retry-policy {:call retry/retry-decision}})

(defn with-defaults
  "OVERRIDES merged over the builtin defaults, stage by stage — what a ctx
   builder seats under `:loop-stages` when a run wants some of its own. A
   ctx's stage map is read whole (`for-ctx`), so this is where the defaults and
   the overrides meet."
  [overrides]
  (merge-with merge defaults overrides))

(defn base
  "The stage map CTX's composition provides: each stage's row-provided value
   merged over its builtin — a stage with no row mounted merges nil, which is
   the builtin. A ctx outside a composition (nil, a bare test ctx) reads the
   defaults."
  [ctx]
  (reduce-kv (fn [m stage default]
               (assoc m stage (merge default (srce/get ctx stage))))
             {}
             defaults))

(defn for-ctx
  "CTX's loop stage map, read once per attempt. The nearest `:loop-stages` on
   the ctx chain wins whole — a run's own map, not a merge over `base` — and a
   ctx that carries none reads what the composition provides (`base`, which
   falls back to the defaults outside one)."
  [ctx]
  (if-some [stage-atom (srce/attr ctx :loop-stages)]
    @stage-atom
    (base ctx)))

(defn- own
  "The stage atom CTX itself carries, or nil — no inheritance here, so a
   refresh names its target instead of reaching up the chain."
  [ctx]
  (when (srce/ctx? ctx) (:loop-stages ctx)))

(defn refresh!
  "Re-seat CTX's own stage atom with what its composition provides, so a
   reloaded guard or retry row reaches the next attempt; an in-flight attempt
   keeps the map it read. `for-ctx` inherits, a refresh does not: a ctx that
   carries no stage atom of its own is left alone."
  [ctx]
  (when-some [stage-atom (own ctx)]
    (reset! stage-atom (base ctx))))

;; ─── The stage rows ──────────────────────────────────────────────────────

(defn- stage-value
  "STAGE's service value: the builtin, with CONFIG as its `:config` when the
   row gave one. A non-map CONFIG is refused — a stage cannot interpret it, and
   a silently ignored typo reads exactly like an applied override (the rule the
   tool rows' own `:config` follows)."
  [stage config]
  (when-not (or (nil? config) (map? config))
    (throw (ex-info (str "a " (name stage) " row's :config must be a map, got: "
                         (pr-str (type config)))
                    {:type :kmet.app/bad-stage-config
                     :stage stage
                     :config config})))
  (cond-> (get defaults stage)
    config (assoc :config config)))

(defn apply-stage
  "The apply for STAGE's row: provide the builtin implementation as the stage's
   service, with the row's `:config` as the stage's config — a composition may
   set the guard's threshold or the retry budget without a settings file. The
   value goes into the mount site's scope, so the loop and every agent ctx below
   it see it while the entry stays this row's effect."
  [stage]
  (fn [ctx config]
    (srce/provide! ctx stage (stage-value stage config) {:in (:parent ctx)})))

(srce/defplugin loop-guard-row
  {:name :loop-guard
   :provide #{:loop-guard}
   :apply (apply-stage :loop-guard)})

(srce/defplugin retry-policy-row
  {:name :retry-policy
   :provide #{:retry-policy}
   :apply (apply-stage :retry-policy)})
