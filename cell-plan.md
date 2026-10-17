# kmet Cell — implementation plan

Transitional: the build document for `modules/cell`. Design, contracts and
rationale live in `modules/cell/src/kmet/cell/cell.md`; this file carries only
what the code will make obvious — the shape (§1), the mechanics (§2) and the
phases (§3). It shrinks as they land: §1 goes when P2 does, §2 when `deps.edn`
and `ls` answer it, and a landed phase's row leaves §3 (see cell.md §9).

## Contents

1. [The shape, end to end](#1-the-shape-end-to-end)
2. [Module mechanics](#2-module-mechanics)
3. [Staged plan](#3-staged-plan)

---

## 1. The shape, end to end

The smallest complete reading of this document: the module's surface, the
host's side, and what comes back — the reading P0 and P1 are built from.
**Scaffolding: deleted when P2 lands (cell.md §9)**, because by then `kmet.cell.core`
and `kmet.agent.runtime` are the shape and a second copy of it can only drift.

```clojure
;; ─── the module (kmet.cell.core) ───────────────────────────────────────────
(runtime host)                ; {:resolve [[:builtin f] [:ext g]]}
(check rt entries)            ; [] | [err …]                    pure
(mount! rt entries)           ; → node         (mount! rt entries {:parent node})
(dispose! node)               ; (dispose! node owner)           LIFO per owner
(register! node owner k v)    ; → deregister-fn                 what :start uses
(composition node)            ; → snapshot, flat per composition; once per attempt
explain                       ; → string

;; ─── the agent's units (kmet.agent.runtime) ────────────────────────────────
(def builtin-units
  {:kmet/agent-loop {:config agent-loop-schema
                     :start  (fn [node self]   ; installed at mount, owned by this slot
                               (register! node self {:on-event :agent-settled} on-settled))
                     :handle run-agent-turn    ; runs *and* owns slots
                     :units  {:inner        {:handle run-inner}
                              :settle       {:handle run-settle}
                              :retry-policy {:config [:map [:max-retries :int]]
                                             :handle backoff}
                              :loop-guard   {:config [:map [:threshold :int]]
                                             :handle guard}}}
   :kmet/retry      {:handle backoff}
   :kmet/compaction {:handle compact!}
   :kmet/session    {:handle open-session}})

(defn runtime []
  (cell/runtime {:resolve [[:builtin (fn [nm] (get builtin-units nm))]
                           [:ext     (fn [nm] (extensions/unit-handle nm))]]}))

(defn unit-handle [node id] (:handle (get (cell/composition node) id)))  ; host helper

;; the loop's control flow stays here (cell.md §A.2) — iteration, routing, retry order
(defn run-agent-turn [node input]
  (let [u (cell/composition node :agent-loop)]      ; its own slots, once per attempt
    (loop [attempt 1]
      ((:handle (:inner u)) node (turn-context node input))
      (case (turn-outcome msg)                       ; routing as data, owned here
        :completed ((:handle (:settle u)) node msg)
        :retry     (if (transient? err)
                     (do ((:handle (:retry-policy u)) attempt) (recur (inc attempt)))
                     (flush-quietly! node))
        :overflow  (do (compact-once! node) (recur attempt))
        :aborted   (flush-quietly! node)))))

;; ─── the mount list (settings.edn → entries) ───────────────────────────────
[{:id :agent-loop   :name :kmet/agent-loop}
 {:id :compaction   :name :kmet/compaction :config {:token-threshold 120000}}
 {:id :retry-policy :into :agent-loop     :name :kmet/retry :config {:max-retries 5}}
 {:id :budget       :into :agent-loop     :name "ext:budget" :config {:usd 5.00}}]
```

Mounting and unloading are the whole lifecycle:

```clojure
(cell/mount! rt entries)
;; → check (resolve :name, validate :config, the cell.md §4 checks — plan built, kept
;;   internal, discarded) → run each :start (registrations collected onto the
;;   slot's owner) → publish the snapshot

(cell/mount! rt (mapv #(if (= :retry-policy (:id %))
                         (assoc % :config {:max-retries 9}) %)
                      entries))
;; → diff by :id → dispose that owner (LIFO) → remount → :start again

(cell/mount! rt (into [] (remove #(= :budget (:id %))) entries))
;; → dispose [:app :agent-loop :budget]: its {:on-event :turn-end} deregisters

(cell/dispose! node :ext/budget)   ; one owner — an extension unloading (cell.md §8.2)
(cell/dispose! node)               ; whole node: children before parents, LIFO, logged
```

And what a caller reads:

```clojure
(cell/composition node :agent-loop)
;; {:loop-guard    {:handle #'guard   :config {:threshold 3}
;;                  :owner [:app :agent-loop :loop-guard]}
;;  :retry-policy {:handle #'backoff :config {:max-retries 9} :owner […]}
;;  :inner        {:handle #'run-inner  :owner […]}
;;  :settle       {:handle #'run-settle :owner […]}
;;  :budget       {:handle #'ext-budget :config {:usd 5.00}
;;                 :owner [:app :agent-loop :budget] :keys #{[:on-event :turn-end]}}}

(cell/check rt [{:id :retry :name :kmet/retryy}])
;; [{:path [:app :retry] :entry :retry :key :name
;;   :error "no backend resolved :kmet/retryy (tried :builtin, :ext)"}]
```

Two things the example makes visible. The framework never sees `:inner`,
`:retry-policy`, `:turn-end` or `:overflow` — a group's slot keys and a host's
event names are the host's, and that is why the module can stay this small. And
a unit's handle receives *its* node, so the same `:kmet/agent-loop` descriptor
mounts as the root loop and as a subagent's loop, each with its own slots and
disposal (cell.md §5.6).

## 2. Module mechanics

### 2.1 Dependencies and rank

```clojure
;; modules/cell/deps.edn
{:paths ["src" "test"]
 :deps {}}
```

Rank **0** in `modules.edn`, first in classpath order; the constraint and
what it forces are in `cell.md` cell.md §9.

### 2.2 Files and the guard

```
src/kmet/cell/
├── core.clj     the seven public fns
├── spec.clj     descriptors, entries, the checks, resolution, error data
├── scope.clj    the store: registries, owners, LIFO disposal
├── mount.clj    entries → node; diffing; snapshot; dispose
└── explain.clj  the rendering
```

~420 lines. There is no `graph.clj` (two checks, ~50 lines, in `spec`) and no
`resolve.clj` (resolution is three functions in `spec`). `mount` also owns
`:start` execution and the parent link.

`test/kmet/cell/test_self_contained.clj`, stricter than the `libs` version:
**no `kmet.*` require at all.** The `libs` guard is the precedent, including
its lesson — it reads the `ns` form rather than regexing the file, because the
regex version silently checked almost nothing (the `.*?` stopped at the first
`)` inside a docstring).

### 2.3 Tests

Toy data throughout (leaf handles as counters, names from a two-entry backend
map) — that *is* the proof of genericity, and it is how the checks get
exercised properly: mount an entry whose name no backend resolves, and assert
the error names the entry, the slot and every backend tried. Namespaces:
`test_spec`, `test_scope`, `test_mount`, `test_explain`,
`test_self_contained`.

### 2.4 Wiring

| file | change |
|---|---|
| `modules.edn` | `{:name "cell" :src "modules/cell/src" :test "modules/cell/test" :rank 0}`, first |
| `modules/cell/deps.edn` | as §2.1 |
| `deps.edn` (root) | `kmetia/cell {:local/root "modules/cell"}` **and** `"modules/cell/src"` in `:jolt/build :embed` |
| `bb.edn` | both roots added to `:paths`, in manifest order |
| `modules/agent/deps.edn` | `kmetia/cell {:local/root "../cell"}` |
| root `AGENTS.md` topic map, `modules/agent/src/kmet/README.md` | entry pointing at the design document |

`test/kmet/test_modules_manifest.clj` fails until those literals agree with
`modules.edn`, and `test/kmet/test_module_deps.clj` fails if a higher-ranked
module sneaks in — the wiring is self-enforcing. Test discovery needs no
registry: `kmet.tasks.runner/all-namespaces` derives from the tree via
`kmet.tasks.modules/test-namespaces`.

---

## 3. Staged plan

Each phase is independently landable and gated by the changed-file tasks
(`bb test-changed`, `bb lint-changed`, `bb format-check-changed`) plus
`test-layer-guard` / `test-headless-guard` / `test-self-contained` as the
regression proof. **Framework first, agent second: no file under `modules/agent`
changes before P2**, and the framework's whole surface exists before the first
unit is mounted in the tree.

| phase | side | what lands | notes |
|---|---|---|---|
| **P0** | framework | `scope` (store, owners, LIFO disposal), `spec` (descriptors, entries, resolution, config validation, both checks), `core` (`runtime`, `check`, `register!`, `dispose!`); toy tests; module wiring | additive — the module exists and nothing uses it yet |
| **P1** | framework | `mount!` and the `:id` diff (add / replace / dispose) in `mount`, `explain`, `composition`, the `{:parent …}` arity while it is being written | additive; `core` completes to its seven functions |
| **P2** | agent | `kmet.agent.runtime` (`cell/runtime` + `:builtin` backend); the 15 registries consolidated behind one mounted store; `loop-guard` as the first slot; `/units`; a per-buffer decision on each of the 13 things `bootstrap` used to reset | deletes the 15 dispatchers, both bootstrap halves, the load-order hack; **no behavior change** |
| **P3** | agent | `run-agent-turn` wrapped as one group; its stage fns lifted into slots it calls; the routing `case` becomes a data table the loop's namespace owns | straight refactor — no new execution model (cell.md §A.2) |
| **P4** | agent | session runtime, TUI and extension surfaces as entries; one mount table; old config keys as aliases | the pending/bound duality is gone |
| **P5** | agent | `extension.edn` `:config`/`:units`; `:ext` backend; reload = `mount!` diff + `dispose!` owner; `extensions/core.cljc` splits into loader/manifest/lifecycle; the ABI's `init(api)` registrations map onto the extension unit's `:start` | unload becomes `dispose!` on the extension's owner |
| **P6** | agent | presets as a selector over the entry list | DSH's "the agent loop is itself a plugin", earned |
| **P7** (unscheduled) | agent | subagents: a tool unit mounts a child node with its own model, tool subset and hooks (cell.md §5.6) | pi's `durable` is recorded *open* in `pi-alignment.md`; no kmet scope decision |

P0 and P1 are additive and behaviour-free: nothing in the tree changes meaning,
so a subtle difference is impossible rather than merely unlikely. P2 is
deliberately behavior-preserving, and it is the change where a subtle
difference would be hardest to notice; P3, a pure refactor, is the
*lowest*-risk phase after it — which is the main reason for the cell.md §A.2 cuts.

Landing a phase also deletes the scaffolding that phase makes redundant (cell.md §9):
P2's landing removes §1, P5's removes the host sketches in cell.md §8, and a landed
phase's row leaves this table.

