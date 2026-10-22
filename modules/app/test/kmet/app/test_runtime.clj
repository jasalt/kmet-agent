(ns kmet.app.test-runtime
  "kmet.app.runtime — the boot and the submission point.

   The boot's contract is that a composed row list becomes a live ctx or a
   `:kmet.app/boot-failed` naming each row that did not mount; the submission
   point's contract is that a change from another thread is *not applied* until
   the boundary runs (`new-app.md`, landing 2), and that the boundary drains to
   a fixpoint including the kernel's own pass."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.events :as events]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]))

(def events-row
  "The built-in row the app's composition mounts: the event bus."
  {:id :events :plugin 'kmet.app.events/plugin})

(defn- boot-events []
  (runtime/boot {:rows [events-row]}))

(defn- error-type [f]
  (try (f) nil (catch Exception e (:type (ex-data e)))))

(defn- deadline
  "Run THUNK on another thread and wait for it: the submission is what crosses
   threads, so the test must too."
  [thunk]
  (deref (future (thunk)) 2000 ::timeout))

;; ── boot ──────────────────────────────────────────────────────────────────

(deftest boot-mounts-the-composed-rows
  (let [{:keys [ctx fibers rows]} (boot-events)]
    (try
      (is (= 1 rows))
      (is (= 1 (count fibers)))
      (is (= :active (:state @(first fibers))))
      (is (= #{:events} (:provides @(first fibers))))
      (testing "the service is shared with the row's siblings, the app scope"
        (let [service (srce/get ctx :events)]
          (is (some? service) "provided into the root's scope, not just the row's subtree")
          (is (some? (srce/get (:ctx @(first fibers)) :events))
              "a child ctx (the row's own mount) sees it too")
          (let [heard (atom [])]
            (events/on! ctx :turn-start (fn [e] (swap! heard conj (:n e))))
            ((:emit! service) :turn-start {:type :turn-start :n 1})
            (is (= [1] @heard) "the service emits into the app scope"))))
      (finally (runtime/shutdown! {:ctx ctx})))
    ;; count=, not empty?: a failing assertion pr-strs its arguments, and a ctx
    ;; is cyclic (it holds the kernel, the runtimes and their fibers).
    (is (= 0 (count (srce/children ctx))) "a shutdown leaves no fiber behind")
    (is (= 0 (count (srce/services ctx))))))

(deftest boot-refuses-two-plugins-declaring-one-event-differently
  (let [declaring (fn [name mode doc]
                    {:name name
                     :events {:turn-start {:mode mode :domain :loop :doc doc}}
                     :apply (fn [_ _] nil)})
        e (fn [rows]
            (try (runtime/boot {:rows rows}) nil (catch Exception e e)))]
    (is (nil? (e [{:id :a :plugin (declaring :a :emit "same")}
                  {:id :b :plugin (declaring :b :emit "same")}]))
        "two rows may re-declare an event identically")
    (let [err (e [{:id :a :plugin (declaring :a :emit "one")}
                  {:id :b :plugin (declaring :b :collect-last "two")}])]
      (is (= :kmet.app/event-conflict (:type (ex-data err))))
      (is (= [:turn-start] (mapv :event (:conflicts (ex-data err)))))
      (is (= [:a :b] (mapv :plugin (:declarations (first (:conflicts (ex-data err))))))
          "the conflict names both declarations"))))

(deftest boot-reports-a-row-that-could-not-mount
  (testing "a pending fiber names what it waits for"
    (let [e (try (runtime/boot {:rows [{:id :waits
                                        :plugin {:name :waits
                                                 :inject [:nothing-provides-this]
                                                 :apply (fn [_ _] nil)}}]})
                 nil
                 (catch Exception e e))]
      (is (= :kmet.app/boot-failed (:type (ex-data e))))
      (is (= [{:id :waits :state :pending :error nil
               :waiting-for [:nothing-provides-this]}]
             (:fibers (ex-data e))))))

  (testing "a throwing apply is a boot failure, not a run"
    (let [e (try (runtime/boot {:rows [{:id :throws
                                        :plugin {:name :throws
                                                 :apply (fn [_ _] (throw (ex-info "boom" {})))}}]})
                 nil
                 (catch Exception e e))]
      (is (= :kmet.app/boot-failed (:type (ex-data e))))
      (is (= [:throws] (mapv :id (:fibers (ex-data e)))))
      (is (= :failed (:state (first (:fibers (ex-data e))))))))

  (testing "a row whose source does not resolve"
    (is (= :kmet.app/unresolved-source
           (error-type #(runtime/boot {:rows [{:id :missing
                                               :plugin 'kmet.app.test-runtime/no-such-plugin}]}))))
    (is (= :kmet.app/external-source
           (error-type #(runtime/boot {:rows [{:id :ext :plugin :ext/unknown}]})))
        "an :ext/… id belongs to the extension host, which has not landed")))

;; ── the submission point ──────────────────────────────────────────────────

(deftest a-submission-from-another-thread-waits-for-the-boundary
  (let [{:keys [ctx]} (boot-events)
        heard (atom [])]
    (try
      (is (runtime/submitting? ctx))
      (is (nil? (deadline #(runtime/submit!
                            ctx (fn [] (events/on! ctx :turn-start
                                                   (fn [e] (swap! heard conj (:n e))))))))
          "submit! returns without running the change")
      (srce/emit! ctx :turn-start {:type :turn-start :n 1})
      (is (= [] @heard) "the hook is not registered yet — the boundary has not run")
      (is (= {:applied 1 :rounds 1} (runtime/boundary! ctx)))
      (srce/emit! ctx :turn-start {:type :turn-start :n 2})
      (is (= [2] @heard) "the boundary applied it")
      (is (= {:applied 0 :rounds 1} (runtime/boundary! ctx))
          "a boundary with nothing submitted runs the kernel pass and stops")
      (finally (runtime/shutdown! {:ctx ctx})))))

(deftest a-submitted-mount-lands-at-the-boundary
  (let [{:keys [ctx]} (boot-events)]
    (try
      (let [mounted (atom nil)]
        (deadline #(runtime/submit!
                    ctx (fn []
                          (reset! mounted
                                  (srce/plugin! ctx {:name :late
                                                     :provide #{:late}
                                                     :apply (fn [c _]
                                                              (srce/provide! c :late :here
                                                                             {:in (:parent c)}))})))))
        (is (nil? @mounted))
        (runtime/boundary! ctx)
        (is (= :active (:state @@mounted)))
        (is (= :here (srce/get ctx :late)) "the mount's provide crossed the boundary"))
      (finally (runtime/shutdown! {:ctx ctx})))))

(deftest drain-runs-in-order-and-survives-a-throw
  (let [{:keys [ctx]} (boot-events)
        seen (atom [])]
    (try
      (runtime/submit! ctx (fn [] (swap! seen conj :first)))
      (runtime/submit! ctx (fn [] (throw (ex-info "boom" {}))))
      (runtime/submit! ctx (fn [] (swap! seen conj :third)))
      (is (= 3 (runtime/drain! ctx)))
      (is (= [:first :third] @seen) "one throwing change does not stop the rest")
      (is (= 0 (runtime/drain! ctx)))
      (finally (runtime/shutdown! {:ctx ctx})))))

(deftest the-boundary-drains-to-a-fixpoint
  (let [{:keys [ctx]} (boot-events)
        seen (atom [])]
    (try
      (runtime/submit! ctx (fn []
                             (swap! seen conj :first)
                             (runtime/submit! ctx (fn [] (swap! seen conj :nested)))))
      (is (= {:applied 2 :rounds 2} (runtime/boundary! ctx)))
      (is (= [:first :nested] @seen) "a submission made while draining runs in the next round")
      (finally (runtime/shutdown! {:ctx ctx})))))

(deftest submitting-outside-a-runtime-is-an-error
  (is (= :kmet.app/no-submissions
         (error-type #(runtime/submit! (srce/root) (fn [] nil))))
      "a dropped change would be silent; the runtime refuses instead")
  (is (false? (runtime/submitting? (srce/root)))))
