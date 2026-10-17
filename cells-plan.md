# kmet Cells — implementation plan

Transitional: the build document for `modules/cells`. Design, contracts and
rationale live in `modules/cells/src/kmet/cells/cells.md`; this file carries only
what the code will make obvious — the shape (§1) and the phases (§2). It
shrinks as they land: the mechanics came out with P0, §1 goes when P2 does, and
a landed phase's row leaves the phase table (see cells.md §9).

## Contents

1. [The shape, end to end](#1-the-shape-end-to-end)
2. [Staged plan](#2-staged-plan)

---

## 1. The shape, end to end

The smallest complete reading of this document: the module's surface, the
host's side, and what comes back — the reading P0 and P1 were built from.
**Scaffolding: deleted when P2 lands (cells.md §9)**, because by then `kmet.cells.core`
and `kmet.agent.runtime` are the shape and a second copy of it can only drift.

```clojure
;; ─── the module (kmet.cells.core) ───────────────────────────────────────────
(runtime host)                ; {:resolve [[:builtin f] [:ext g]]}
(check rt entries)            ; [] | [err …]                    pure
(mount! rt entries)           ; → node         (mount! rt entries {:parent node})
(dispose! node)               ; (dispose! node owner)           LIFO per owner
(register! node owner k v)    ; → deregister-fn                 what :start uses
(composition node)            ; → snapshot, flat per composition; once per attempt
(explain snapshot)            ; → string

;; ─── the agent's units (kmet.agent.runtime) ────────────────────────────────
(def builtin-units
  {:kmet/agent-loop {:config agent-loop-schema
                     :start  (fn [node owner]  ; installed at mount, owned by this slot path
                               (register! node owner {:on-event :agent-settled} on-settled))
                     :handle run-agent-turn    ; runs *and* owns slots
                     :units  {:inner        {:handle run-inner}
                              :settle       {:handle run-settle}
                              :retry-policy {:config [:map [:max-retries :int]]
                                             :handle backoff}
                              :loop-guard   {:config [:map [:threshold :int]]
                                             :handle guard}}}
   :kmet/retry      {:config [:map [:max-retries :int]]
                     :handle backoff}
   :kmet/compaction {:config [:map [:token-threshold :int]]
                     :handle compact!}
   :kmet/session    {:handle open-session}})

(defn runtime []
  (cells/runtime {:resolve [[:builtin (fn [nm] (get builtin-units nm))]
                           [:ext     (fn [nm] (extensions/unit-handle nm))]]}))

(defn unit-handle [node id] (:handle (get (cells/composition node) id)))  ; host helper

;; the loop's control flow stays here (cells.md §A.2) — iteration, routing, retry order
(defn run-agent-turn [node input]
  (let [u (cells/composition node :agent-loop)]      ; its own slots, once per attempt
    (loop [attempt 1]
      (let [msg ((:handle (:inner u)) node (turn-context node input))
            err (turn-error msg)]                    ; nil on a clean turn; outcomes stay today's
        (case (turn-outcome msg)                     ; routing as data, owned here
          :completed ((:handle (:settle u)) node msg)
          :error     (cond
                       (transient? err) (do ((:handle (:retry-policy u)) attempt)
                                            (recur (inc attempt)))
                       (overflow? err)  (do (compact-once! node) (recur attempt))
                       :else            (flush-quietly! node))
          :aborted   (flush-quietly! node))))))

;; ─── the mount list (settings.edn → entries) ───────────────────────────────
[{:id :agent-loop   :name :kmet/agent-loop}
 {:id :compaction   :name :kmet/compaction :config {:token-threshold 120000}}
 {:id :retry-policy :into :agent-loop     :name :kmet/retry :config {:max-retries 5}}
 {:id :budget       :into :agent-loop     :name :budget/spend :config {:usd 5.00}}]
;; :budget/spend is the budget extension's unit — extension units are named by
;; keyword in the extension's namespace (cells.md §8.2); the :ext backend resolves it.
```

Mounting and unloading are the whole lifecycle:

```clojure
(cells/mount! rt entries)
;; → check (resolve :name, validate :config, the cells.md §4 checks — plan built, kept
;;   internal, discarded) → run each :start (registrations collected onto the
;;   slot's owner) → publish the snapshot

(cells/mount! rt (mapv #(if (= :retry-policy (:id %))
                         (assoc % :config {:max-retries 9}) %)
                      entries))
;; → diff by :id → dispose that owner (LIFO) → remount → :start again

(cells/mount! rt (into [] (remove #(= :budget (:id %))) entries))
;; → unload the budget extension: the diff drops :budget and disposes its owner
;;   [:app :agent-loop :budget], whose {:on-event :turn-end} deregisters (cells.md §8.2)

(cells/dispose! node [:app :agent-loop :budget])  ; one owner's registrations, LIFO
(cells/dispose! node)                             ; whole node: children before parents, LIFO, logged
```

And what a caller reads:

```clojure
(cells/composition node :agent-loop)
;; {:loop-guard    {:handle #'guard   :config {:threshold 3}
;;                  :owner [:app :agent-loop :loop-guard]}
;;  :retry-policy {:handle #'backoff :config {:max-retries 9} :owner […]}
;;  :inner        {:handle #'run-inner  :owner […]}
;;  :settle       {:handle #'run-settle :owner […]}
;;  :budget       {:handle #'budget-spend :config {:usd 5.00}
;;                 :owner [:app :agent-loop :budget] :keys #{[:on-event :turn-end]}}}

(cells/check rt [{:id :retry :name :kmet/retryy}])
;; [{:path [:app :retry] :entry :retry :key :name
;;   :error "no backend resolved :kmet/retryy (tried :builtin, :ext)"}]
```

Two things the example makes visible. The framework never sees `:inner`,
`:retry-policy`, `:turn-end` or `:overflow` — a group's slot keys and a host's
event names are the host's, and that is why the module can stay this small. And
a unit's handle receives *its* node, so the same `:kmet/agent-loop` descriptor
mounts as the root loop and as a subagent's loop, each with its own slots and
disposal (cells.md §5.6).

## 2. Staged plan

**P0 and P1 have landed** — the store, `spec`, the seven facade fns, the
mount table with its `:id` diff, the snapshot and `explain`, the toy tests and
the module wiring are in the tree; their rows left this table (the git log is
the record). Each phase is independently landable and gated by the changed-file
tasks (`bb test-changed`, `bb lint-changed`, `bb format-check-changed`) plus
`test-self-contained` / `test_modules_manifest` / `test_module_deps` as the
regression proof (the agent guards, `test-layer-guard` and
`test-headless-guard`, join with P2). **Framework first, agent second: no agent
source or behavior changes before P2**; the agent's `deps.edn` dep on cells
lands with P2, its first consumer. The framework's whole surface exists before
the first unit is mounted in the tree.

| phase | side | what lands | notes |
|---|---|---|---|
| **P2** | agent | `kmet.agent.runtime` (`cells/runtime` + `:builtin` backend); the 15 registries consolidated behind one mounted store; `loop-guard` as the first slot (read directly by the loop until P3); snapshot taken once per attempt (cells.md §5.4); `/units` (new surface); a per-registry decision on each of the 13 items `bootstrap` used to reset | the root node mounts in `core/-main` before `packages/load-extensions!`, so extension-time registrations already have an owner — that ordering is what retires the pending buffers; deletes the 15 dispatchers, both bootstrap halves, the load-order hack; **no behavior change to existing paths** |
| **P3** | agent | `run-agent-turn` wrapped as one group; its stage fns lifted into slots it calls; the routing `case` becomes a data table the loop's namespace owns | straight refactor — no new execution model (cells.md §A.2) |
| **P4** | agent | session runtime, TUI and extension surfaces as entries; one mount table; old config keys as aliases | the four ad-hoc mount mechanisms become one entry list (cells.md §1.3) |
| **P5** | agent | `extension.edn` `:config`/`:units`; `:ext` backend; reload = `mount!` diff + `dispose!` owner; `extensions/core.cljc` splits into loader/manifest/lifecycle; the ABI's `init(api)` registrations map onto the extension unit's `:start` | unload becomes the cells.md §8.2 three steps: `mount!` without the extension's entries (the diff disposes its owners), `shutdown(api)`, `loader/unload!` |
| **P6** | agent | presets as a selector over the entry list | DSH's "the agent loop is itself a plugin", earned |
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cells.md §5.6) | pi's `durable` is recorded *open* in `pi-alignment.md`; no kmet scope decision |

Two cells.md §12 decisions gate P2 rather than a later phase: **Q1**
(capability snapshot timing) lands with P2, where the tool-unload race the
snapshot closes lives; and **Q4** (`composition`'s data form as an ABI) was
settled with P1 — §5.4 pins the shape before `/units` ships, and `explain`'s
string form is only the cosmetic half.

P2 is deliberately behavior-preserving for existing paths, and it is the change
where a subtle difference would be hardest to notice; P3, a pure refactor, is
the *lowest*-risk phase after P2 — which is the main reason for the cells.md §A.2 cuts.

Landing a phase also deletes the scaffolding that phase makes redundant (cells.md §9):
P2's landing removes §1, P5's removes the host sketches in cells.md §8, and a landed
phase's row leaves this table.

