(ns kmet.app.test-front-end-gates
  "Landing 8's gate tests: what a front end may rely on at the composition
   level, not the widget wiring (the modes' own tests stub the handle).

   A front end creates its agent through `:agents` and never owns it: the
   agent is a child of the ctx the front end mounts on, `:agent/created` says
   so, a settings write touches the agent it was called with and no other, two
   agents settle their own runs on their own contexts, and an image prompt
   reaches the model as an image block while a queued image is refused."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.agent.config :as cfg]
            [kmet.agent.session.runtime :as runtime]
            [kmet.ai.llm :as llm]
            [kmet.app.core :as core]
            [kmet.app.events :as events]
            [kmet.app.runtime :as runtime-app]
            [kmet.app.test-support :as support]
            [kmet.srce.core :as srce]))

(def ^:private sessions-dir "target/test-front-end-gates")

(defn- boot!
  "The booted `:tui` composition: the real rows, so `:agents` is the loop
   driver's registry and the handles it creates are the ones a front end
   reads."
  []
  (fs/create-dirs sessions-dir)
  (runtime-app/boot (core/compose-config {:profile :tui}) {}))

(defn- config-for
  [model]
  {:provider :opencode-go
   :model model
   :session-dir sessions-dir})

(defn- shutdown!
  [boot]
  (srce/dispose-subtree! (:ctx boot))
  (fs/delete-tree sessions-dir))

(deftest a-created-agent-is-in-the-tree-and-announced
  (testing "a front end's agent is a child context of the ctx it mounted on —
            the `[:app :agent/<id>]` line --dump-state prints — and
            :agent/created announces it"
    (let [boot (boot!)]
      (try
        (let [ctx (:ctx boot)
              agents (srce/get ctx :agents)
              heard (atom [])
              off (events/on! ctx :agent/created (fn [ev] (swap! heard conj ev)))]
          (try
            (let [handle ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})]
              (is (= [(:id handle)] (mapv :id @heard))
                  "the composition hears the agent it created, once")
              (is (contains? (set (map :name (srce/children ctx)))
                             (keyword "agent" (:id handle)))
                  "the agent lives under the ctx the front end mounts on")
              (is (= (:id handle) (get-in ((:list agents) ctx) [0 :id]))
                  "and the registry lists it"))
            (finally (off))))
        (finally (shutdown! boot))))))

(deftest a-settings-write-touches-only-its-agent
  (testing "two agents of one composition hold their own model and thinking
            level; writing one leaves the other's state and run context alone"
    (let [boot (boot!)]
      (try
        (let [ctx (:ctx boot)
              agents (srce/get ctx :agents)
              a ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})
              b ((:create! agents) ctx {:config (config-for "deepseek-v4-pro")})
              ;; a Model record without the catalog: set-model! stores the
              ;; record's provider and id, which is what the run reads
              target {:provider :opencode-go :id "minimax-m3" :name "Minimax M3"}]
          ((:set-model! agents) a target)
          (is (= "minimax-m3" (runtime/model a)))
          (is (= "deepseek-v4-pro" (runtime/model b))
              "the sibling's model is untouched")
          ((:set-thinking-level! agents) a 5)
          (is (= 5 (runtime/thinking-level a)))
          (is (= 5 (get-in (runtime/extension-context a) [:thinking-level])))
          (is (not= 5 (get-in (runtime/extension-context b) [:thinking-level]))
              "the context the sibling's handlers would build is unchanged"))
        (finally (shutdown! boot))))))

(deftest each-agent-settles-on-its-own-context
  (testing "two runs in one composition settle their own promises: a listener
            on an agent's ctx hears that agent only"
    (let [boot (boot!)]
      (try
        (let [ctx (:ctx boot)
              agents (srce/get ctx :agents)
              a ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})
              b ((:create! agents) ctx {:config (config-for "deepseek-v4-pro")})
              settled-a (promise)
              settled-b (promise)
              off-a (events/on! (:ctx a) :agent-settled (fn [ev] (deliver settled-a ev)))
              off-b (events/on! (:ctx b) :agent-settled (fn [ev] (deliver settled-b ev)))]
          (try
            (with-redefs [cfg/get-api-key (fn [_] "test-key")
                          llm/send-message (fn [opts]
                                             (future
                                               (when-let [done (:on-done opts)]
                                                 (done :stop))))]
              ((:send! agents) a "to a" {:target :next-turn})
              ((:send! agents) b "to b" {:target :next-turn})
              (is (= :completed (:outcome (deref settled-a 20000 ::timeout))))
              (is (= :completed (:outcome (deref settled-b 20000 ::timeout))))
              (let [user-text (fn [h]
                                (->> @(:messages (:agent h))
                                     (filter #(= :user (:role %)))
                                     (mapv (comp :text first :content))))]
                (is (= ["to a"] (user-text a)) "each turn is the agent's own context")
                (is (= ["to b"] (user-text b)))))
            (finally
              (off-a)
              (off-b))))
        (finally (shutdown! boot))))))

(deftest an-image-prompt-reaches-the-model
  (testing "an image sent with a prompt is an image block on the request the
            model sees; a queued image is refused by name rather than dropped"
    (let [boot (boot!)]
      (try
        (let [ctx (:ctx boot)
              agents (srce/get ctx :agents)
              handle ((:create! agents) ctx {:config (config-for "claude-haiku-5-5")})
              requests (atom [])
              image {:type :image :data "aGVsbG8=" :mime-type "image/png"}]
          (with-redefs [cfg/get-api-key (fn [_] "test-key")
                        llm/send-message (fn [opts]
                                           (swap! requests conj opts)
                                           (future
                                             (when-let [done (:on-done opts)]
                                               (done :stop))))]
            ;; the prompt is a message map when images ride along (the
            ;; editor's path), a plain string for text-only sends
            ((:send! agents) handle {:text "look at this" :images [image]}
                             {:target :next-turn})
            (is (support/wait-until #(seq @requests)) "the model was called")
            (let [content (-> @requests first :messages last :content)]
              (is (some #(= "aGVsbG8=" (:data %)) content)
                  "the provider request carries the image bytes")
              (is (some #(= "look at this" (:text %)) content)
                  "and the text rides with it"))
            (is (= :kmet.app/unsupported-images
                   (try
                     ((:send! agents) handle {:text "queued" :images [image]}
                                      {:target :steering})
                     :none
                     (catch Exception e (:type (ex-data e)))))
                "a queued message carries text, so an image may only start a prompt")))
        (finally (shutdown! boot))))))
