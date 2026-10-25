(ns kmet.app.test-support
  "Shared helpers for the app's entry-point tests."
  (:require [babashka.fs :as fs]
            [kmet.app.config :as cfg]
            [kmet.libs.concurrent :as concurrent]
            [kmet.ai.llm :as llm]
            [kmet.ai.models :as models]
            [kmet.app.core :as core]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]))

(defn run-main-into
  "`main` on ARGS with stdout going to OUT, a writer the caller keeps. A mode
   that streams while it runs (rpc) is read *during* the run this way: the
   test watches OUT for the record it is waiting for and only then does what it
   was holding back (usually: closing stdin). Returns `{:code :err}`."
  [args out]
  (let [code (atom nil)
        err (java.io.StringWriter.)]
    (binding [*out* out
              *err* err]
      (reset! code (core/main args)))
    {:code @code :err (str err)}))

(defn run-main
  "The `{:code :out :err}` of kmet2's entry on ARGS — `main` returns the exit
   code and prints, so a command is read without forking a process."
  [args]
  (let [out (java.io.StringWriter.)
        {:keys [code err]} (run-main-into args out)]
    {:code code :out (str out) :err err}))

(defn wait-until
  "Poll PRED every 5ms until it is true or TIMEOUT-MS (default 5s) pass, then
   answer whether it became true. For a test waiting on another thread's work:
   a bounded wait for the actual condition, not a sleep hoping it happened. The
   deadline is the monotonic clock, as every duration in kmet is."
  ([pred] (wait-until pred 5000))
  ([pred timeout-ms]
   (let [deadline (+ (concurrent/monotonic-ms) (long timeout-ms))]
     (loop []
       (cond
         (pred) true
         (< (concurrent/monotonic-ms) deadline) (do (Thread/sleep 5) (recur))
         :else false)))))

;; ── mode runs ─────────────────────────────────────────────────────────────
;;
;; A mode test boots the real composition and drives one mode through `main`,
;; so the helpers here are the ones every mode needs: a settable model, a
;; stubbed provider, and a session log under target/ (an entry-point test must
;; not write to the agent dir a developer's kmet reads).

(def sessions-dir
  "Where a mode test's session log goes."
  "target/test-modes")

(defn settings
  "What the driver loads for a run: a provider and model that resolve."
  []
  {:session-dir sessions-dir
   :provider :opencode-go
   :model "deepseek-v4-flash"})

(defn with-mode-run
  "Run F with the model stubbed by SEND-MESSAGE (a `send-message` stub) and the
   settings above.

   The session dir is emptied *before* the run starts, never after it: a mode
   test with a stalled provider can still be finishing a run when the next test
   begins, and a cleanup that deleted the directory out from under it would turn
   that into an unrelated failure. What the tests need is a clean start, and
   `target/` is thrown away by `bb clean`."
  [send-message f]
  (fs/delete-tree sessions-dir)
  (fs/create-dirs sessions-dir)
  (with-redefs [cfg/load-config (fn [& _] (settings))
                cfg/get-api-key (fn [_] "test-key")
                llm/send-message send-message]
    (f)))

(defn answering
  "A `send-message` stub that streams TEXT and stops."
  [text]
  (fn [opts]
    (future
      (when-let [on-text (:on-text opts)] (on-text text))
      (when-let [on-done (:on-done opts)] (on-done :stop))
      :done)))

(defn with-model-catalog
  "Run F with the catalog a `--model` pattern resolves against: one reasoning
   model."
  [f]
  (with-redefs [models/get-provider (fn [p] {:id p})
                models/get-models (fn [& _] [{:provider :opencode-go
                                              :id "deepseek-v4-flash"
                                              :reasoning true}])]
    (f)))

;; ── the composition ───────────────────────────────────────────────────────
;;
;; A test whose subject is the composed app (the rows, the process registries,
;; the extension host the rows bind) boots it once for the namespace. The ctx is
;; the boot's root: it holds every service a front end would `srce/get`, which
;; is what kmet1's `app-runtime/ctx` used to hand out through the shim.

(def ^:dynamic *composition*
  "The boot map `with-composition` bound, or nil outside it."
  nil)

(defn with-composition
  "clojure.test :once fixture: boot the app's composition for the namespace and
   dispose it after. Read its root with `composition-ctx`.

   The boot is the test process's own: a namespace that themes or binds the
   extension host publishes a process registry, so it must not share a
   composition with another namespace's fixture. It composes the profile, not a
   pinned settings map — the rows read the developer's config at mount, so a
   test that depends on a resource set pins that config (or stubs the lookup),
   as `test-lifecycle`'s resource test stubs `io/resource`."
  [f]
  (let [boot (runtime/boot (core/compose-config {:profile :tui}) {})]
    (try
      (binding [*composition* boot]
        (f))
      (finally
        (srce/dispose-subtree! (:ctx boot))))))

(defn composition-ctx
  "The composed root ctx `with-composition` bound."
  []
  (:ctx *composition*))

(defn with-host
  "clojure.test :each fixture: this namespace needs a bound extension host.

   The loop's tool path and the render path ask the host for its hooks, and a
   bare test process has no composition to bind one. `kmet.test-utils` owns the
   fixture (a fresh registry over a bare ctx, restored and disposed in its
   `finally`), so the app's tests and the root tree's bind the same thing."
  [f]
  (tu/with-extension-host f))
