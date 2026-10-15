(ns kmet.app.test-ui-port
  "Front-end extension binding + UI request/response contract (engine.md
   step 4, §4.5): bind-extensions! carries the front-end kind (ctx.mode /
   ctx.has-ui), the headless port auto-answers, and request-ui!/respond!
   pair a request with its answer."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest testing]]
            [kmet.app.extensions :as ex]
            [kmet.app.session :as session]
            [kmet.app.session-runtime :as rt]
            [kmet.test-utils :as tu]))

;; The binding is process-global state; every test starts and ends headless.
(t/use-fixtures :each
  (fn [f]
    (ex/unbind-extensions!)
    (try (f)
         (finally (ex/unbind-extensions!)))))

(defn- gate-engine
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
      (t/is (= "/tmp/x" (:cwd ctx))))))

(deftest test-headless-port-auto-answers
  (testing "the degraded port answers every capability without a front end"
    (let [port (ex/headless-ui-port)]
      (t/is (nil? ((:notify port) "hi" :info)))
      (t/is (= "" ((:get-editor-text port))))
      (t/is (false? ((:get-tools-expanded port))))
      (t/is (= :collapsed ((:get-tool-display-mode port))))
      (t/is (= {:success false :error "UI not available"}
               ((:set-theme port) "dark")))
      (t/is (fn? ((:on-terminal-input port) (fn [_] nil)))
            ":on-terminal-input returns a no-op unsubscriber")
      (t/is (nil? (deref ((:custom port) (fn [_ _ _ _] nil) {}) 100 ::timeout))
            ":custom returns an already-resolved promise")
      (t/is (nil? ((:request port) {:kind :select})))
      (t/is (false? ((:request port) {:kind :confirm})))
      (t/is (nil? ((:request port) {:kind :editor}))))))

(deftest test-headless-port-custom-via-extension-api
  (testing "ext/ui-custom through the headless port resolves instead of nil"
    (ex/bind-extensions! {:mode :print :has-ui false :ui (ex/headless-ui-port)})
    (t/is (nil? (deref (ex/ui-custom (fn [_ _ _ done] (done nil)) {})
                       100 ::timeout)))))

(deftest test-request-ui-falls-back-to-defaults
  (testing "without a :request capability the degraded default answers"
    (let [engine (gate-engine)]
      (t/is (false? (deref (rt/request-ui! engine :confirm {}) 100 ::timeout)))
      (t/is (nil? (deref (rt/request-ui! engine :select {}) 100 ::timeout)))
      (t/is (true? (deref (rt/request-ui! engine :confirm {:default true})
                          100 ::timeout))
            ":default overrides the kind default"))))

(deftest test-request-ui-sync-port
  (testing "a port answering in-process returns the value — nil included"
    (let [seen (atom [])
          engine (gate-engine)]
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
        (t/is (empty? @(:pending-ui engine))
              "a synchronous answer leaves nothing pending")))))

(deftest test-request-ui-async-port-responds
  (testing "a forwarding port leaves the promise pending until respond!"
    (let [requests (atom [])
          engine (gate-engine)]
      (ex/bind-extensions!
       {:mode :rpc :has-ui false
        :ui {:request (fn [request] (swap! requests conj request)
                        ex/request-pending)}})
      (let [p (rt/request-ui! engine :input {:title "name?"})
            {:keys [id]} (first @requests)]
        (t/is (not (realized? p)))
        (t/is (= 1 (count @(:pending-ui engine))))
        (t/is (nil? (rt/respond! engine id "marko")))
        (t/is (= "marko" (deref p 100 ::timeout)))
        (t/is (empty? @(:pending-ui engine)))
        (t/is (nil? (rt/respond! engine id "twice"))
              "an already-answered id is ignored")))))

(deftest test-request-ui-throwing-port
  (testing "a throwing port propagates but leaves nothing pending"
    (let [engine (gate-engine)]
      (ex/bind-extensions! {:mode :interactive :has-ui true
                            :ui {:request (fn [_] (throw (ex-info "boom" {})))}})
      (t/is (thrown? Exception (rt/request-ui! engine :confirm {})))
      (t/is (empty? @(:pending-ui engine))))))

(deftest test-dispose-resolves-pending-requests
  (testing "dispose! cannot leave an extension dialog parked"
    (let [engine (gate-engine)]
      (ex/bind-extensions! {:mode :rpc :has-ui false
                            :ui {:request (fn [_] ex/request-pending)}})
      (let [p (rt/request-ui! engine :confirm {})]
        (t/is (not (realized? p)))
        (rt/dispose! engine)
        (t/is (nil? (deref p 100 ::timeout)))
        (t/is (empty? @(:pending-ui engine)))
        (t/is (= :print (ex/extension-mode))
              "dispose! unbinds the front-end surface")))))

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
            (finally (rt/dispose! engine))))
        (finally (fs/delete-tree dir))))))
