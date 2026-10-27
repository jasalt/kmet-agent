(ns kmet.app.tools.test-tool-tables
  "A tool table belongs to a registry, not to the process.

   The registry takes its handle as the first argument, and every tool — the
   built-ins included — reaches the table as a registration: a tool row
   (`:tool/read`, …) injects `:tools` and mounts its record as its own effect
   (`kmet.app.tools.registry/mount-tool!`). Two agents therefore hold two tables
   with nothing shared, and a row's disposal takes its tool with it. That is the
   property landing 4c-iii-c's gate needs; it is cheap to pin now because the
   alternative — a static table under the registrations — is exactly what a
   process default would be."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.profile :as profile]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.srce.core :as srce]
            [kmet.test-utils :as tu]))

(defn- probe
  "A minimal tool named PROBE whose result says where it came from."
  [from]
  {:name "probe" :label "Probe" :description "d"
   :execute (fn [_] {:content from})})

(defn- tool-rows
  "The composition's tool rows, as `:bundle/minimal` declares them."
  []
  (->> (:rows (profile/bundle (profile/builtin) :bundle/minimal))
       (filter #(str/starts-with? (str (:id %)) ":tool/"))))

(t/deftest a-registration-belongs-to-its-own-table
  (let [a (registry/atom-registry)
        b (registry/atom-registry)]
    (tools/register-tool! a (probe "from-a"))
    (t/is (contains? (tools/get-all-tools a) "probe")
          "the handle the tool was registered in sees it")
    (t/is (not (contains? (tools/get-all-tools b) "probe"))
          "and no other handle does")))

(t/deftest dispatch-follows-the-handle
  (let [a (registry/atom-registry)
        b (registry/atom-registry)]
    (tools/register-tool! a (probe "from-a"))
    (tools/register-tool! b (probe "from-b"))
    (t/is (= "from-a" (:content (tools/execute-tool a "probe" {}))))
    (t/is (= "from-b" (:content (tools/execute-tool b "probe" {})))
          "the same name resolves to each handle's own tool")))

(t/deftest the-rows-are-the-name-list
  (t/is (= (mapv tools/port-name tools/builtin-tool-names)
           (mapv :id (tool-rows)))
        "each row is named :tool/<model name>, and the settings layer's static
         name list is that same list in that same order")
  (t/is (= (count tools/builtin-tool-names) (count (distinct tools/builtin-tool-names)))
        "no tool is named twice"))

(t/deftest a-row-registers-the-tool-the-model-sees
  (let [{:keys [tools]} (tu/tool-ctx)]
    (t/is (= (set tools/builtin-tool-names)
             (set (keys (tools/get-all-tools tools))))
          "mounting the rows is what puts the built-ins in the table")
    (t/is (= "read" (:name (tools/get-tool tools "read")))
          "and the record the model calls is the row's own")))

(t/deftest a-tool-leaves-with-its-row
  (let [{:keys [ctx tools]} (tu/tool-ctx)
        row (fn [name]
              (srce/mount-fiber
               (first (filter #(= name (srce/attr % :name)) (srce/children ctx)))))]
    (t/is (contains? (tools/get-all-tools tools) "bash"))
    (srce/dispose! (row :tool/bash))
    (t/is (not (contains? (tools/get-all-tools tools) "bash"))
          "the registration was the row's effect, so disposing the row removed it")
    (t/is (contains? (tools/get-all-tools tools) "read")
          "and only its own tool went")))

(t/deftest the-run-code-record-dispatches-through-its-own-handle
  ;; The `:tool/run_code` row builds its record for the handle it was mounted
  ;; over, so the record's sandbox surface (`select-tools` over its own
  ;; registry) is that handle's: a tool registered in A is callable from A's
  ;; record and unknown to B's, and that surface is what the bridge gates on.
  ;; This is the per-agent tool table 4c-iii-c names, one seam down — and what
  ;; the shape assertions above cannot see.
  (let [a (:tools (tu/tool-ctx))
        b (:tools (tu/tool-ctx))
        run (fn [h] (:execute (get (tools/get-all-tools h) "run_code")))
        code "(emit (:content @(tools/call \"probe\" {})))"]
    (tools/register-tool! a (probe "from-a"))
    (let [from-a (:content ((run a) {:code code} nil nil nil))
          from-b (:content ((run b) {:code code} nil nil nil))]
      (t/is (= "from-a" from-a)
            "A's record reaches the tool registered in A")
      (t/is (str/includes? from-b "Tool not active in this run_code call: probe")
            "B's record does not — its surface came from its own handle"))))
