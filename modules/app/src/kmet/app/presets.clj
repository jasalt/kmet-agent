(ns kmet.app.presets
  "A preset is a bundle mounted under one ctx.

   `new-app.md` § Profiles and presets: a **bundle** is reusable rows plus
   patches, a **profile** stacks bundles at the root, and a **preset** mounts
   one under an agent's child ctx. The machinery is the same on purpose — one
   composition language, two mount points — so a preset is named like a bundle
   and composed by `kmet.app.profile`, never re-declared here.

   What a preset is *for*: per-agent composition (`new-app.md` § Multi-session
   server mode). The agent's child ctx inherits root services through the
   chain, so the difference a preset makes is a row the child provides itself —
   a `:permissions` policy for one agent, a shadowed `:tools`, a listener.
   Mounting on the child ctx is also what keeps the row's lifetime the agent's:
   the fiber is under the child, so disposing the agent disposes it.

   The rows must be **active** after the mount settles. A preset that composes
   but does not mount (a name nothing provides, an `apply` that threw) is a
   composition bug, and the agent it was composing for must not be created with
   half its world — so the failure is thrown here, before the agent exists, and
   `:agents/create!`'s rollback releases the child ctx."
  (:require [kmet.app.profile :as profile]
            [kmet.app.runtime :as runtime]
            [kmet.srce.core :as srce]))

(defn mount!
  "Mount PRESET-NAME's rows under CTX and return `{:preset :rows :fibers}`.
   Throws `:kmet.app/preset-failed` when a row is not active, naming it.

   CTX is the ctx the preset is for — an agent's child ctx from
   `:agents/create!`'s `:setup` — and the fibers live and die with it."
  ([ctx preset-name] (mount! ctx (profile/builtin) preset-name))
  ([ctx table preset-name]
   (let [{:keys [rows]} (profile/compose [(profile/bundle table preset-name)])
         fibers (runtime/mount-rows! ctx rows)]
     (srce/settle! ctx)
     (let [failed (runtime/inactive-rows rows fibers)]
       (when (seq failed)
         (doseq [f failed] (srce/log! ctx :error "preset row did not mount" f))
         (throw (ex-info (str "preset " (pr-str preset-name) " did not mount: "
                              (mapv :id failed))
                         {:type :kmet.app/preset-failed
                          :preset preset-name
                          :fibers failed}))))
     {:preset preset-name :rows (count rows) :fibers fibers})))

(defn setup
  "The `:setup` a `:agents/create!` opts carries: mount PRESET-NAME under the
   agent's child ctx — `(create! ctx {:setup (presets/setup :bundle/gated)})`.
   `setup` runs before the factory, so no agent observes a half-composed world."
  ([preset-name] (setup (profile/builtin) preset-name))
  ([table preset-name]
   (fn [ctx _id] (mount! ctx table preset-name))))
