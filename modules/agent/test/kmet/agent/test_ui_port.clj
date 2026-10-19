(ns kmet.agent.test-ui-port
  "Front-end extension binding + UI request/response contract: bind-extensions!
   carries the front-end kind (ctx.mode /
   ctx.has-ui), the headless port auto-answers, and request-ui!/respond!
   pair a request with its answer."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.agent.extensions.core :as ex]
            [kmet.agent.runtime :as app]
            [kmet.agent.session.core :as session]
            [kmet.agent.session.runtime :as rt]
            [kmet.libs.scope :as scope]
            [kmet.test-utils :as tu]))

;; The binding and the app tree's registrations are process-global state;
;; every test starts and ends headless with a cleared tree (the bootstrap test
;; loads the developer's own skills).
(t/use-fixtures :each
  (fn [f]
    (ex/unbind-extensions!)
    (tu/clear-app-registries!)
    (try (f)
         (finally (ex/unbind-extensions!)
                  (tu/clear-app-registries!)))))

(defn- bare-engine
  "A bare engine with just enough agent state for request/dispose tests."
  []
  (tu/test-engine {:agent-state (atom {:compacting? (atom false)})}))

(deftest test-bind-extensions-sets-kind-and-port
  (testing "bind-extensions! installs the front-end kind and capability map"
    (ex/bind-extensions! {:mode :interactive :has-ui true
                          :ui {:notify (fn [_ _] :notified)}})
    (t/is (= :interactive (ex/extension-mode)))
    (t/is (true? (ex/extension-has-ui?)))
    (t/is (= :notified (ex/ui-call :notify "hi" :info)))
    (t/is (nil? (ex/ui-call :missing)))))

(deftest test-unbind-restores-headless-defaults
  (ex/bind-extensions! {:mode :gui :has-ui true :ui {:notify (fn [_ _] nil)}})
  (ex/unbind-extensions!)
  (t/is (= :print (ex/extension-mode)))
  (t/is (false? (ex/extension-has-ui?)))
  (t/is (nil? (ex/ui-call :notify "hi" :info))))

(deftest test-install-front-end-owns-the-port
  (testing "install-front-end! registers the port on the tree; disposal restores headless"
    (let [node (app/node)]
      (try
        (ex/install-front-end! node {:mode :interactive :has-ui true
                                     :ui {:notify (fn [_ _] :notified)}})
        (t/is (= :interactive (ex/extension-mode)))
        (t/is (= :notified (ex/ui-call :notify "hi" :info)))
        (t/is (contains? (scope/owner-keys node [:app :ui]) :kmet/ui-port)
              "the port is the [:app :ui] owner's registration")
        (finally
          (scope/dispose-owner! node [:app :ui])))
      (t/is (= :print (ex/extension-mode))
            "disposal restores the headless binding")
      (t/is (empty? (scope/owner-keys node [:app :ui]))))))

(deftest test-port-only-swap-preserves-kind
  (testing "set-ui-registry!/clear-ui-registry! swap the port, not the kind"
    (ex/bind-extensions! {:mode :gui :has-ui true :ui {}})
    (ex/set-ui-registry! {:get-editor-text (fn [] "draft")})
    (t/is (= :gui (ex/extension-mode)))
    (t/is (= "draft" (ex/ui-call :get-editor-text)))
    (ex/clear-ui-registry!)
    (t/is (= :gui (ex/extension-mode)))
    (t/is (nil? (ex/ui-call :get-editor-text)))))

(deftest test-build-extension-context-carries-mode-and-port
  (testing "ctx.mode / ctx.has-ui come from the binding; :build-context merges"
    (ex/bind-extensions! {:mode :interactive :has-ui true
                          :ui {:build-context (fn [] {:cwd "/tmp/x"})}})
    (let [ctx (ex/build-extension-context)]
      (t/is (= :interactive (:mode ctx)))
      (t/is (true? (:has-ui ctx)))
      (t/is (= "/tmp/x" (:cwd ctx)))))
  (testing "the binding wins over a :build-context that restates the kind"
    (ex/bind-extensions! {:mode :gui :has-ui true
                          :ui {:build-context (fn [] {:mode :print :has-ui false})}})
    (let [ctx (ex/build-extension-context)]
      (t/is (= :gui (:mode ctx)))
      (t/is (true? (:has-ui ctx))))))

(deftest test-headless-port-auto-answers
  (testing "the degraded port answers every capability without a front end"
    (ex/bind-extensions! {:mode :print :has-ui false :ui (ex/headless-ui-port)})
    (t/is (nil? (ex/ui-notify "hi" :info))
          "omitted setters fall through ui-call as no-ops")
    (t/is (= "" (ex/ui-get-editor-text)))
    (t/is (false? (ex/ui-get-tools-expanded)))
    (t/is (= :collapsed (ex/ui-get-tool-display-mode)))
    (t/is (= {:success false :error "UI not available"} (ex/ui-set-theme "dark")))
    (t/is (fn? (ex/ui-on-terminal-input (fn [_] nil)))
          ":on-terminal-input returns a no-op unsubscriber")
    (t/is (nil? (deref (ex/ui-custom (fn [_ _ _ _] nil) {}) 100 ::timeout))
          ":custom returns an already-resolved promise")
    (t/is (nil? (ex/ui-call :request {:kind :select})))
    (t/is (false? (ex/ui-call :request {:kind :confirm})))))

(deftest test-headless-port-custom-via-extension-api
  (testing "ext/ui-custom through the headless port resolves instead of nil"
    (ex/bind-extensions! {:mode :print :has-ui false :ui (ex/headless-ui-port)})
    (t/is (nil? (deref (ex/ui-custom (fn [_ _ _ done] (done nil)) {})
                       100 ::timeout)))))

(deftest test-request-ui-falls-back-to-defaults
  (testing "without a :request capability the degraded default answers"
    (let [engine (bare-engine)]
      (t/is (false? (deref (rt/request-ui! engine :confirm {}) 100 ::timeout)))
      (t/is (nil? (deref (rt/request-ui! engine :select {}) 100 ::timeout)))
      (t/is (true? (deref (rt/request-ui! engine :confirm {:default true})
                          100 ::timeout))
            ":default overrides the kind default"))))

(deftest test-request-ui-sync-port
  (testing "a port answering in-process returns the value — nil included"
    (let [seen (atom [])
          engine (bare-engine)]
      (ex/bind-extensions!
       {:mode :interactive :has-ui true
        :ui {:request (fn [request]
                        (swap! seen conj request)
                        (case (:kind request)
                          :confirm true
                          :select nil))}})
      (t/is (true? (deref (rt/request-ui! engine :confirm {:title "ok?"})
                          100 ::timeout)))
      (t/is (nil? (deref (rt/request-ui! engine :select {}) 100 ::timeout))
            "a synchronous nil answer resolves (not treated as pending)")
      (let [{:keys [type id kind title]} (first @seen)]
        (t/is (= :ui-request type))
        (t/is (= :confirm kind))
        (t/is (= "ok?" title))
        (t/is (string? id))
        (t/is (not (contains? (first @seen) :default))
              "the degraded default is not part of the request payload")
        (t/is (empty? @(:pending-ui engine))
              "a synchronous answer leaves nothing pending")))))

(deftest test-request-ui-async-port-responds
  (testing "a forwarding port leaves the promise pending until respond!"
    (let [requests (atom [])
          engine (bare-engine)]
      (ex/bind-extensions!
       {:mode :rpc :has-ui false
        :ui {:request (fn [request] (swap! requests conj request)
                        ex/request-pending)}})
      (let [p (rt/request-ui! engine :input {:title "name?" :default "fallback"})
            {:keys [id]} (first @requests)]
        (t/is (not (realized? p)))
        (t/is (= 1 (count @(:pending-ui engine))))
        (t/is (= "fallback" (rt/request-default engine id))
              "a forwarding port's own deadline resolves with the recorded default")
        (t/is (nil? (rt/respond! engine id "marko")))
        (t/is (= "marko" (deref p 100 ::timeout)))
        (t/is (empty? @(:pending-ui engine)))
        (t/is (nil? (rt/request-default engine id)) "unknown once answered")
        (t/is (nil? (rt/respond! engine id "twice"))
              "an already-answered id is ignored")))))

(deftest test-request-ui-throwing-port
  (testing "a throwing port propagates but leaves nothing pending"
    (let [engine (bare-engine)]
      (ex/bind-extensions! {:mode :interactive :has-ui true
                            :ui {:request (fn [_] (throw (ex-info "boom" {})))}})
      (t/is (thrown? Exception (rt/request-ui! engine :confirm {})))
      (t/is (empty? @(:pending-ui engine))))))

(deftest test-dispose-resolves-pending-requests
  (testing "dispose! cannot leave an extension dialog parked"
    (let [engine (bare-engine)]
      (ex/bind-extensions! {:mode :rpc :has-ui false
                            :ui {:request (fn [_] ex/request-pending)}})
      (let [p (rt/request-ui! engine :confirm {})]
        (t/is (not (realized? p)))
        (rt/dispose! engine)
        (t/is (false? (deref p 100 ::timeout))
              "the kind's degraded default resolves it, not nil")
        (t/is (empty? @(:pending-ui engine)))
        (t/is (= :print (ex/extension-mode))
              "dispose! unbinds the front-end surface"))))
  (testing "a request with :default resolves with it on dispose"
    (let [engine (bare-engine)]
      (ex/bind-extensions! {:mode :rpc :has-ui false
                            :ui {:request (fn [_] ex/request-pending)}})
      (let [p (rt/request-ui! engine :select {:default :cancelled})]
        (rt/dispose! engine)
        (t/is (= :cancelled (deref p 100 ::timeout)))))))

(deftest test-bootstrap-binds-headless-print-defaults
  (testing "a print-mode engine gets the degraded port, ctx says :print"
    (let [dir (str "target/test-ui-port-" (System/currentTimeMillis))
          sess (session/create-session dir {:cwd (str (fs/cwd))})]
      (try
        (let [engine (rt/bootstrap {:provider :opencode-go
                                    :model "deepseek-v4-flash"}
                                   {:session sess})]
          (try
            (t/is (= :print (ex/extension-mode)))
            (t/is (false? (ex/extension-has-ui?)))
            (let [ctx (ex/build-extension-context)]
              (t/is (= :print (:mode ctx)))
              (t/is (false? (:has-ui ctx))))
            (t/is (false? (deref (rt/request-ui! engine :confirm {})
                                 100 ::timeout))
                  "a print-mode extension dialog answers with the default")
            (t/is (vector? (ex/runtime-call :get-active-tools))
                  "the runtime capabilities are installed for every front end")
            (t/is (false? (ex/runtime-call :set-model nil))
                  "a nil model is rejected without touching settings")
            (finally (rt/dispose! engine)))
          (t/is (nil? (ex/runtime-call :get-active-tools))
                "dispose! clears the runtime capabilities"))
        (finally (fs/delete-tree dir))))))
