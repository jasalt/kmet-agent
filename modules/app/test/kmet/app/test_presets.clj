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
        (is (= "no approval channel is mounted — this run cannot ask"
               (:reason (permissions/check (:ctx gated) a-call)))
            "the gated agent asks, and the bundle's default approver denies")
        (is (nil? (srce/get (:ctx boot) :permissions))
            "the preset's row is the agent's, not the root's"))
      (finally (srce/dispose-subtree! (:ctx boot))))))

(deftest a-preset-that-cannot-mount-leaves-no-agent
  (let [boot (booted)
        agents (srce/get (:ctx boot) :agents)
        broken {:bundles {:bundle/broken
                          {:rows [{:id :wants-missing
                                   :plugin {:name :wants-missing
                                            :inject [:nothing-provides-this]
                                            :apply (fn [_ _])}}]}}
                :profiles {}}]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"preset :bundle/broken did not mount"
                            ((:create! agents) (:ctx boot)
                                               {:setup (presets/setup broken :bundle/broken)}))
          "a row that never becomes active is a failed setup, not a half-built agent")
      (is (empty? ((:list agents) (:ctx boot)))
          "and the child ctx is rolled back with it")
      (finally (srce/dispose-subtree! (:ctx boot))))))

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
