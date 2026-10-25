(ns kmet.app.test-presets
  "Presets: a bundle mounted under one ctx.

   Two things are pinned here. A preset composes per agent — the same booted
   root holds an agent whose tool calls are gated and one whose are not, and
   neither sees the other's policy. And a preset that cannot mount leaves no
   agent behind: the setup failure rolls the child ctx back the way a failing
   factory does."
  (:require [clojure.test :refer [deftest is]]
            [kmet.app.core :as core]
            [kmet.app.permissions :as permissions]
            [kmet.app.presets :as presets]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]))

(defn- booted
  "A booted composition with the loop's `:agents` factory live. No mode runs:
   the rows are mounted and the test drives the registry directly."
  []
  (runtime/boot (core/compose-config {:profile :tui}) {}))

(def ^:private config
  "What `create!` needs to place a session somewhere disposable, rather than in
   the agent dir a developer's kmet reads."
  {:session-dir "target/test-presets"})

(def ^:private a-call
  "A tool call the gate can be asked about."
  {:tool-name "bash" :args {:command "echo hi"} :tool-call-id "call-1"})

(deftest a-preset-composes-one-agents-world
  (let [boot (booted)
        agents (srce/get (:ctx boot) :agents)]
    (try
      (let [plain ((:create! agents) (:ctx boot) {})
            gated ((:create! agents) (:ctx boot)
                                     {:setup (presets/setup :bundle/gated)})]
        (is (nil? (permissions/check (:ctx plain) a-call))
            "no policy on the chain allows, so a plain agent's call proceeds")
        (is (= "no terminal is running — nobody can ask"
               (:reason (permissions/check (:ctx gated) a-call)))
            (str "the gated agent asks, and the TUI composition's terminal"
                 " approver denies because no terminal is running"))
        (is (nil? (srce/get (:ctx boot) :permissions))
            "the preset's row is the agent's, not the root's"))
      (finally (srce/dispose-subtree! (:ctx boot))))))

(deftest a-preset-that-cannot-mount-leaves-no-agent
  (let [boot (booted)
        agents (srce/get (:ctx boot) :agents)
        child-ctx (atom nil)
        broken {:bundles {:bundle/broken
                          {:rows [{:id :wants-missing
                                   :plugin {:name :wants-missing
                                            :inject [:nothing-provides-this]
                                            :apply (fn [_ _])}}]}}
                :profiles {}}]
    (try
      (let [thrown (try ((:create! agents) (:ctx boot)
                                           {:config config
                                            :setup (fn [ctx _id]
                                                     (reset! child-ctx ctx)
                                                     (presets/mount! ctx broken :bundle/broken))})
                        ::no-throw
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :kmet.app/preset-failed (:type (ex-data thrown)))
            "a composition bug keeps its own type rather than becoming a generic creation failure")
        (is (re-find #"preset :bundle/broken did not mount" (ex-message thrown))
            "and names the preset and the row that did not mount"))
      (is (empty? ((:list agents) (:ctx boot)))
          "no handle is published")
      (is (not-any? #(identical? % @child-ctx) (srce/children (:ctx boot)))
          "and the child ctx is released from the tree, not left extended in it")
      (finally (srce/dispose-subtree! (:ctx boot))))))

(deftest a-setup-that-is-not-a-fn-is-refused
  ;; An EDN composition can put a keyword where this expects a fn, so the
  ;; opt has its own failure: named before anything is claimed or extended.
  (let [boot (booted)
        agents (srce/get (:ctx boot) :agents)]
    (try
      (is (= :kmet.app/bad-setup
             (try ((:create! agents) (:ctx boot) {:setup :bundle/gated})
                  ::no-throw
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (empty? ((:list agents) (:ctx boot)))
          "nothing was minted, so nothing is listed")
      (finally (srce/dispose-subtree! (:ctx boot))))))

(deftest a-failed-preset-unwinds-what-it-mounted
  ;; A caller mounting onto a ctx it means to keep — not `create!`, which
  ;; releases the whole child ctx on a throw — must still be left with no trace
  ;; of the preset: half of a policy row set is the one thing a gate must not be.
  (let [ctx (srce/root {:name :preset-test})
        broken {:bundles {:bundle/half
                          {:rows [{:id :half-gate
                                   :plugin 'kmet.app.permissions/permissions
                                   :config {:policy :ask-all}}
                                  {:id :wants-missing
                                   :plugin {:name :wants-missing
                                            :inject [:nothing-provides-this]
                                            :apply (fn [_ _])}}]}}
                :profiles {}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"preset :bundle/half did not mount"
                          (presets/mount! ctx broken :bundle/half)))
    (is (nil? (srce/get ctx :permissions))
        "the row that did mount was unwound before the throw")))

(deftest a-profile-may-stack-a-preset-bundle
  ;; The gate's composition leg: `:rpc-ask` is `:bundle/minimal` plus
  ;; `:bundle/gated`, so the profile is two stacked layers and the policy arrives
  ;; as a bundle rather than as a patch reaching into one.
  (let [composed (core/compose-config {:profile :rpc-ask})
        config (some #(when (= :permissions (:id %)) %) (:rows composed))]
    (is (= {:policy :ask-all} (:config config))
        "the stacked bundle contributes its row")
    (is (some #(= :mode-rpc (:id %)) (:rows composed))
        "beside the profile's own mode row")))

(deftest a-profile-stacking-a-preset-bundle-boots
  (let [boot (runtime/boot (core/compose-config {:profile :rpc-ask}) {})]
    (try
      (let [policy (srce/get (:ctx boot) :permissions)]
        (is (some? policy)
            "the stacked bundle's row is live at the root after a real boot")
        (is (= :ask ((:policy policy) (:ctx boot) a-call))
            "and it is the gate's policy, not the allow-all default"))
      (finally (srce/dispose-subtree! (:ctx boot))))))
