(ns kmet.ui.tui.test-tui-ask
  "T-3: a preset reaches a TUI run's child ctx.

   The row's config names the bundles, `run-opts` carries the names into the
   run, and `build-layout`'s create opts mount them under the agent's child ctx
   before the agent exists (7b-ii's `:setup`). `:profile tui-ask` is the
   built-in that names the gate; the plain `:tui` profile names none, so the
   default TUI is unchanged.

   No terminal: the composition's terminal approver is what a gated agent's tool
   call asks, and its answer with no terminal running is the denial asserted
   here."
  (:require [clojure.test :refer [deftest is]]
            [kmet.app.core :as core]
            [kmet.app.permissions :as permissions]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]
            [kmet.ui.tui :as tui-row]
            [kmet.ui.tui.interactive.layout :as layout]))

(def ^:private config
  "Where the run's session goes: a disposable dir, never the developer's own."
  {:session-dir "target/test-tui-ask"})

(defn- mode-row
  "The row `:mode true` marks — the one the entry hands the invocation's input."
  [profile]
  (first (filter :mode (:rows (core/compose-config {:profile profile})))))

(def ^:private a-call
  "A tool call the gate can be asked about."
  {:tool-name "bash" :args {:command "echo hi"} :tool-call-id "call-1"})

(deftest the-profile-names-the-gate-and-the-plain-tui-names-none
  (is (= [:bundle/gated] (get-in (mode-row :tui-ask) [:config :presets]))
      "tui-ask: the row names the gate as a per-agent preset")
  (is (nil? (get-in (mode-row :tui) [:config :presets]))
      "the default TUI names no preset: its agent's world is the root's"))

(deftest the-rows-preset-is-carried-into-the-run-and-mounted-per-agent
  (let [presets (get-in (mode-row :tui-ask) [:config :presets])
        opts ((var tui-row/run-opts) {:presets presets})]
    (is (= presets (:presets opts)) "the row's names reach the run opts")
    (let [boot (runtime/boot (core/compose-config {:profile :tui-ask}) {})
          ctx (:ctx boot)
          agents (srce/get ctx :agents)]
      (try
        (let [create-opts (layout/create-opts config nil opts)]
          (is (fn? (:setup create-opts))
              "the create opts carry a setup: the preset mounts before the agent")
          (let [plain ((:create! agents) ctx {:config config})
                gated ((:create! agents) ctx create-opts)]
            (is (nil? (permissions/check (:ctx plain) a-call))
                "a plain agent's call proceeds: no policy on its chain")
            (is (= "no terminal is running — nobody can ask"
                   (:reason (permissions/check (:ctx gated) a-call)))
                (str "the gated agent asks — the row's preset is that agent's,"
                     " and the composition's terminal approver has no terminal"))
            (is (nil? (srce/get ctx :permissions))
                "the policy row is the agent's, not the root's")))
        (finally (srce/dispose-subtree! ctx))))))
