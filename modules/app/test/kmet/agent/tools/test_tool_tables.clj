(ns kmet.agent.tools.test-tool-tables
  "A tool table belongs to a registry, not to the process.

   The registry takes its handle as the first argument and builds the built-in
   table per handle, so the same name can be a different tool in two tables and
   dispatch follows the handle it was given — one seam down too, where the
   run_code record's sandbox surface is built from its own handle. That is the
   property landing 4c-iii-c's gate needs (two agents, two tool tables); it is
   cheap to pin now because the alternative — a process default — is exactly
   what review removed from this layer."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kmet.agent.registry :as registry]
            [kmet.agent.tools.registry :as tools]))

(defn- probe
  "A minimal tool named PROBE whose result says where it came from."
  [from]
  {:name "probe" :label "Probe" :description "d"
   :execute (fn [_] {:content from})})

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

(t/deftest the-built-in-table-is-per-handle
  (let [a (registry/atom-registry)
        b (registry/atom-registry)]
    (t/is (= (set (keys (tools/built-in-tools a)))
             (set (keys (tools/built-in-tools b))))
          "both handles offer the same built-ins")
    (t/is (= (set tools/builtin-tool-names)
             (set (keys (tools/built-in-tools a))))
          "the static name list matches the table it describes")))

(t/deftest the-run-code-record-dispatches-through-its-own-handle
  ;; `built-in-tools` builds the run_code record per handle, so the record's
  ;; sandbox surface (`select-tools` over its own registry) is that handle's:
  ;; a tool registered in A is callable from A's record and unknown to B's,
  ;; and that surface is what the bridge gates on. This is the per-agent tool
  ;; table 4c-iii-c names, one seam down — and what the shape assertions above
  ;; cannot see.
  (let [a (registry/atom-registry)
        b (registry/atom-registry)
        run (fn [h] (:execute (get (tools/get-all-tools h) "run_code")))
        code "(emit (:content @(tools/call \"probe\" {})))"]
    (tools/register-tool! a (probe "from-a"))
    (let [from-a (:content ((run a) {:code code} nil nil nil))
          from-b (:content ((run b) {:code code} nil nil nil))]
      (t/is (= "from-a" from-a)
            "A's record reaches the tool registered in A")
      (t/is (str/includes? from-b "Tool not active in this run_code call: probe")
            "B's record does not — its surface came from its own handle"))))
