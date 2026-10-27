(ns kmet.app.test-world
  "The process world loads once, in order, and an entry's composition is
   published before its extensions register (new-app.md, 5l-ii)."
  (:require [clojure.test :as t]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.packages :as packages]
            [kmet.ai.image-models :as image-models]
            [kmet.ai.models :as models]
            [kmet.app.root :as root]
            [kmet.app.world :as world]
            [kmet.srce.core :as srce]
            [kmet.libs.theme :as theme]))

(t/deftest the-world-loads-in-order-and-publishes-its-root
  (let [calls (atom [])
        ctx (srce/root {:name :app})
        prev (root/reset-root!)]
    (try
      (with-redefs [models/load-catalogs! #(swap! calls conj :catalogs)
                    image-models/load-image-catalogs! #(swap! calls conj :image-catalogs)
                    models/load-models-config! #(swap! calls conj :models-config)
                    models/get-model-config-error (constantly nil)
                    cfg/init! (fn [] (swap! calls conj :config) {:model "x"})
                    packages/load-extensions!
                    #(swap! calls conj [:extensions (some? (root/ctx))])
                    packages/resolve-theme-paths (constantly ["/t.edn"])
                    theme/load-theme-paths! (fn [paths] (swap! calls conj [:themes paths]))
                    extensions/set-cli-flags!
                    (fn [flags] (swap! calls conj [:flags flags]))]
        (t/is (= {:model "x"} (world/load! {:ext-flags {"probe" "1"}} ctx))
              "the loaded config is the world load's answer"))
      (t/is (= [:catalogs :image-catalogs :models-config :config
                [:extensions true]
                [:themes ["/t.edn"]]
                [:flags {"probe" "1"}]]
               @calls)
            "catalogs first, then config, then the root is published before
             extensions register, then themes, then the flags")
      (t/is (identical? ctx (root/ctx)) "the entry's composition is the process root")
      (finally
        (srce/dispose-subtree! ctx)
        (root/restore-root! prev)))))

(t/deftest an-entry-without-a-composition-publishes-nothing
  (let [prev (root/reset-root!)]
    (try
      (with-redefs [models/load-catalogs! (constantly nil)
                    image-models/load-image-catalogs! (constantly nil)
                    models/load-models-config! (constantly nil)
                    models/get-model-config-error (constantly nil)
                    cfg/init! (constantly {})
                    packages/load-extensions! (constantly nil)
                    packages/resolve-theme-paths (constantly [])
                    theme/load-theme-paths! (constantly nil)
                    extensions/set-cli-flags! (constantly nil)]
        (world/load! {}))
      (t/is (nil? (root/ctx))
            "a world load with no ctx publishes nothing")
      (finally
        (root/restore-root! prev)))))

(t/deftest the-first-published-root-wins
  (let [prev (root/reset-root!)
        ours (srce/root {:name :app})
        other (srce/root {:name :other})]
    (try
      (t/is (identical? ours (root/publish! ours)))
      (t/is (identical? ours (root/publish! other))
            "a second publish cannot strand the tree the process is reading")
      (t/is (identical? ours (root/ctx)))
      (finally
        (srce/dispose-subtree! ours)
        (srce/dispose-subtree! other)
        (root/restore-root! prev)))))
