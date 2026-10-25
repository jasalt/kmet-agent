(ns kmet.agent.test-runtime-capabilities
  "Tests for the runtime-owned extension capabilities (pi: bindCore):
   agent control every front end shares — send-user-message, send-message!,
   set-model, thinking level, active tools. The tests drive the extension
   api against a bare engine, which is the headless path."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.ai.models :as m]
            [kmet.app.extensions.core :as extensions]
            [kmet.app.loop :as agent]
            [kmet.app.session.core :as session]
            [kmet.app.session.driver :as session-driver]
            [kmet.app.config :as cfg]
            [kmet.test-utils :as tu]))

(defn- install-runtime-capabilities!
  "Install the runtime-owned extension capabilities for CS's test engine
   (bootstrap does this in production)."
  [cs]
  (extensions/bind-runtime-capabilities!
   (session-driver/runtime-capabilities (:engine cs))))

(deftest test-send-user-message-queues-while-the-prompt-settles
  (testing "a send during the settle window queues into the running prompt
            instead of starting a concurrent one (pi: prompt() while
            isStreaming queues via streamingBehavior; isStreaming stays true
            until the whole prompt loop finishes, so an :agent-end handler's
            message extends the prompt)"
    (let [model (m/map->Model {:provider :test-native :id "test-model"})
          ag (agent/make-agent-state :provider :test-native :model "test-model")
          ;; The settle window: a prompt is in flight (running-turn? true) but
          ;; the last attempt already reported the agent idle.
          cs (tu/with-test-engine {:agent-state (atom ag)
                                   :config cfg/default-config
                                   :session-atom (atom nil)
                                   :running-turn? (atom true)})]
      (with-redefs [m/providers-atom (atom {:test-native {:models [model]}})]
        (try
          (install-runtime-capabilities! cs)
          (let [started (atom 0)]
            (with-redefs [session-driver/prompt! (fn [& _] (swap! started inc))]
              (t/is (= :idle @(:status ag)) "the attempt already reported idle")
              (extensions/send-user-message "queued" {:deliver-as :follow-up})
              (t/is (= ["queued"] @(:follow-up ag))
                    "queued for the running prompt")
              (t/is (= 0 @started) "no concurrent prompt")
              (extensions/send-user-message "steered" {:deliver-as :steer})
              (t/is (= ["steered"] @(:steering ag)))
              (let [before (count @(:follow-up ag))]
                (extensions/send-message! {:custom-type :note :content "custom"}
                                          {:trigger-turn true :deliver-as :follow-up})
                (t/is (= (inc before) (count @(:follow-up ag)))
                      "a custom message queues as one message-map entry")
                (t/is (map? (last @(:follow-up ag)))))
              (t/is (= 0 @started) "still no concurrent prompt")
              (testing "with no prompt in flight a send starts the run"
                (let [queued-before (vec @(:follow-up ag))]
                  (reset! (:turn-running? (:engine cs)) false)
                  (extensions/send-user-message "fresh" {:deliver-as :follow-up})
                  (t/is (= 1 @started) "idle send starts the run")
                  (t/is (= queued-before @(:follow-up ag))
                        "nothing was queued for the idle send")
                  ;; idle + :steer starts a run too — :steer only means "into
                  ;; the current run" while one is active
                  (let [steered-before (vec @(:steering ag))]
                    (extensions/send-user-message "fresh steer" {:deliver-as :steer})
                    (t/is (= 2 @started) "idle :steer starts the run")
                    (t/is (= steered-before @(:steering ag))
                          "nothing was steered with no run active"))))))
          (finally (extensions/unbind-extensions!)))))))

(deftest test-send-message-delivery-modes
  (testing "a custom message follows pi's sendCustomMessage branches:
            :next-turn queues for the next prompt, a streaming send queues
            into the run (steer/follow-up) or defers without a trigger, and
            an idle send is appended — exactly once, at the delivery point
            (the session entry and the context append never happen twice)"
    (let [dir (-> (fs/create-dirs (fs/path "target" (str "test-send-message-modes-"
                                                         (System/currentTimeMillis))))
                  fs/absolutize str)
          sess (session/create-session dir)
          ag (agent/make-agent-state :session sess)
          cs (tu/with-test-engine {:agent-state (atom ag)
                                   :config cfg/default-config
                                   :session-atom (atom sess)})
          custom-entries #(filter (fn [e] (= :custom-message (:role e)))
                                  (session/get-branch sess))
          custom-context #(filter (fn [m] (= :custom (:role m))) @(:messages ag))]
      (try
        (install-runtime-capabilities! cs)
        (let [send (fn
                     ([m] (extensions/send-message! m))
                     ([m o] (extensions/send-message! m o)))
              started (atom 0)]
          (with-redefs [session-driver/prompt! (fn [& _] (swap! started inc))]
            (testing "idle, no trigger: appended now, no run"
              (send {:custom-type :note :content "idle"})
              (t/is (= 1 (count (custom-entries))) "persisted")
              (t/is (= 1 (count (custom-context))) "appended to the context")
              (t/is (= 0 @started) "no turn"))
            (testing "next turn: queued for the next prompt, even with :trigger-turn"
              (send {:custom-type :note :content "next"}
                    {:trigger-turn true :deliver-as :next-turn})
              (t/is (= 1 (count @(:pending-next-turn ag))))
              (t/is (= 1 (count (custom-entries))) "not persisted yet")
              (t/is (= 1 (count (custom-context))) "not in context yet")
              (t/is (= 0 @started) "next-turn wins over :trigger-turn"))
            (testing "streaming + trigger-turn: queued into the run"
              (reset! (:turn-running? (:engine cs)) true)
              (send {:custom-type :note :content "steer"} {:trigger-turn true})
              (t/is (= 1 (count @(:steering ag))) ":steer is the default mode")
              (send {:custom-type :note :content "follow"}
                    {:trigger-turn true :deliver-as :follow-up})
              (t/is (= 1 (count @(:follow-up ag))) ":follow-up queues separately")
              (t/is (= 1 (count (custom-entries))) "neither is persisted here")
              (t/is (= 1 (count (custom-context))) "nor appended to the context")
              (t/is (= 0 @started) "no concurrent run"))
            (testing "streaming without a trigger: deferred to the end of the turn"
              (send {:custom-type :note :content "deferred"} {:trigger-turn false})
              (t/is (= 1 (count @(:pending-custom ag))))
              (t/is (= 1 (count (custom-entries))) "not persisted yet")
              (t/is (= 1 (count (custom-context))) "not in context yet"))
            (testing "idle + trigger-turn: appended once and starts the run"
              (reset! (:turn-running? (:engine cs)) false)
              (send {:custom-type :note :content "now"} {:trigger-turn true})
              (t/is (= 2 (count (custom-entries))) "persisted once")
              (t/is (= 2 (count (custom-context))) "appended once")
              (t/is (= 1 @started) "starts the run"))))
        (finally
          (extensions/unbind-extensions!)
          (fs/delete-tree dir))))))

(deftest test-set-model-capability
  (testing "the runtime :set-model capability follows the Model record"
    (let [old (m/map->Model {:provider :test-native :id "old"})
          model (m/map->Model {:provider :test-adapter :id "test-model"})
          ag (agent/make-agent-state :provider :test-native :model "old")
          cs (tu/with-test-engine {:agent-state (atom ag)
                                   :config cfg/default-config
                                   :session-atom (atom nil)})
          auth? (atom true)]
      (with-redefs [m/providers-atom (atom {:test-native {:models [old]}
                                            :test-adapter {:models [model]}})
                    m/has-configured-auth (fn [_] @auth?)
                    cfg/set-default-model! (fn [& _] nil)]
        (try
          (install-runtime-capabilities! cs)
          (t/is (true? (extensions/set-model model)))
          (t/is (= :test-adapter @(:provider ag)) "the provider follows the record")
          (t/is (= "test-model" @(:model ag)))
          (reset! auth? false)
          (t/is (false? (extensions/set-model model)) "no configured auth → false")
          (t/is (= "test-model" @(:model ag)) "state unchanged")
          (finally
            (extensions/unbind-extensions!)))))))
