(ns kmet.agent.test-app-ctx
  "kmet1's registrations live in the app root ctx: the root is the composition
   `--app new` boots, the façade routes each tag to the service that owns it, and
   an owner's registrations come back in one call (4c-ii)."
  (:require [clojure.test :as t]
            [kmet.agent.app-runtime :as app-runtime]
            [kmet.app.commands :as commands]
            [kmet.app.registry :as registry]
            [kmet.app.tools.registry :as tools]
            [kmet.app.root :as root]
            [kmet.srce.core :as srce]))

(t/deftest the-root-is-the-composed-app
  (let [ctx (app-runtime/ctx)]
    (t/is (some? ctx) "the app root is a ctx")
    (t/is (identical? ctx (app-runtime/ctx)) "and it is mounted once per process")
    (doseq [service [:events :tools :commands :prompts :skills
                     :sessionPersistence :sessions]]
      (t/is (some? (srce/get ctx service)) (str service " is provided")))))

(t/deftest a-published-root-is-the-process-root
  ;; An entry that boots the composition itself publishes it, so kmet1's shims
  ;; read the tree the entry mounted instead of booting a second one behind it.
  (let [ours (srce/root {:name :app})
        other (srce/root {:name :other})
        prev (root/reset-root!)]
    (try
      (t/is (identical? ours (root/publish! ours)))
      (t/is (identical? ours (app-runtime/ctx))
            "the published root, not a freshly booted composition")
      (t/is (identical? ours (root/publish! other))
            "the first root wins — a second publish cannot strand the tree the
             process is already reading")
      (finally
        (srce/dispose-subtree! ours)
        (srce/dispose-subtree! other)
        (root/restore-root! prev)))))

(t/deftest the-facade-routes-a-tag-to-its-service
  (let [handle (app-runtime/registry)]
    (t/is (identical? handle (app-runtime/registry)) "one façade per process")
    (commands/register-command!
     {:name "routed-cmd" :description "d" :handler (fn [_ _] nil)})
    (t/is (some? (registry/entry (srce/get (app-runtime/ctx) :commands)
                                 :kmet/command "routed-cmd"))
          "a command lands in the :commands service")
    (t/is (nil? (registry/entry (srce/get (app-runtime/ctx) :tools)
                                :kmet/command "routed-cmd"))
          "and not in another service")
    (commands/unregister-command!  "routed-cmd")))

(t/deftest ui-side-tags-have-a-place-to-go
  ;; No seam yet for the extension host's renderers and flags, so they share the
  ;; façade's remainder handle until 4b-ii gives them services.
  (let [handle (app-runtime/registry)]
    (t/is (some? handle))
    (registry/register! handle :kmet/flag "probe-flag" {:type :boolean} :test)
    (t/is (some? (registry/entry handle :kmet/flag "probe-flag"))
          "an unmapped tag still has a handle")
    (registry/deregister-owner! handle :kmet/flag :test)
    (t/is (nil? (registry/entry handle :kmet/flag "probe-flag")))))

(t/deftest one-call-takes-an-owners-registrations
  (let [owner [:app :extensions "probe-ext"]
        handle (app-runtime/registry)]
    (tools/register-tool!
     handle
     {:name "owned-tool" :description "d"
      :parameters {:type "object" :properties {}}
      :execute (fn [_] {:content "ok"})} owner)
    (registry/register! handle :kmet/flag "owned-flag" {:type :boolean} owner)
    (registry/register! handle :kmet/flag "other-flag" {:type :boolean} :someone-else)
    (t/is (= [owner] (vec (keys (registry/by-owner
                                 (srce/get (app-runtime/ctx) :tools)
                                 :kmet/tool "owned-tool")))))
    (try
      (app-runtime/deregister-owner! owner)
      (t/is (nil? (registry/entry (srce/get (app-runtime/ctx) :tools)
                                  :kmet/tool "owned-tool"))
            "the tool went with its owner")
      (t/is (nil? (registry/entry handle :kmet/flag "owned-flag")) "so did the flag")
      (t/is (some? (registry/entry handle :kmet/flag "other-flag"))
            "another owner's registration in the same tag survived")
      (finally
        (registry/deregister-owner! handle :kmet/flag :someone-else)))))
