(ns kmet.ui.tui.interactive.test-layout
  "Rendering plumbing: the dock generation gate and widget-area reactivity, and the
   editor's submit ordering (submitValue clears before onSubmit)."
  (:require [clojure.test :as t :refer [deftest testing]]
            [kmet.test-utils :as tu :refer [strip-ansi]]
            [kmet.tui.components.editor :as editor]
            [kmet.tui.hikap :as hiccup]
            [kmet.libs.hikap.component :as component]
            [kmet.tui.protocols :as protocols]
            [kmet.tui.core :as tui]
            [kmet.ui.tui.interactive.auth :as ia]
            [kmet.ui.tui.interactive.layout :as layout]
            [kmet.ui.tui.interactive.ui-registry :as ui-registry]
            [kmet.agent.commands :as commands]
            [kmet.agent.extensions.core :as extensions]
            [kmet.ui.tui.status-indicator :as status-indicator]
            [kmet.ui.tui.chat-history :as chat-history]
            [kmet.ui.tui.tool-execution :as te]
            [kmet.ui.tui.dock :as dock]
            [kmet.ai.auth :as auth]
            [kmet.agent.loop :as agent]
            [kmet.ui.tui.footer-data-provider :as fdp]
            [kmet.agent.config :as cfg]
            [kmet.agent.tools.core :as tools]
            [kmet.ui.tui.interactive.test-support :as support]))

;; The interactive builtins register the app's whole slash registry; clear it
;; so a later namespace never sees the tree filled by this one.
(t/use-fixtures :each tu/with-clean-app-registries)

;; ─── DSL stage 4 review: dock generation gate + widget-area reactivity ────

(deftest test-dock-membership-handles
  (testing "a release removes only its own component, wherever it sits, so a
            replaced selector's close cannot yank the newer one out of the
            dock (membership replaced pi's activeSelectorToken); release is
            idempotent and restores the CURRENT active editor"
    (with-redefs [tui/tui-set-focus (fn [_ _] nil)
                  tui/tui-request-render (fn [_] nil)]
      (let [ed (editor/make-editor)
            cs (tu/with-test-engine {:tui {}
                                     :dock-stack (atom [])
                                     :current-editor-atom (atom ed)})
            panel-a (status-indicator/make-status-indicator)
            panel-b (editor/make-editor)]
        ;; A mounts, then B replaces it
        (dock/mount! cs panel-a)
        (dock/mount! cs panel-b)
        (t/is (= panel-b (dock/top-component @(:dock-stack cs))) "B recorded")
        (dock/release! cs panel-a)
        (t/is (= panel-b (dock/top-component @(:dock-stack cs)))
              "the absent component's release is inert")
        ;; B's release reveals the editor; a second call is harmless
        (dock/release! cs panel-b)
        (t/is (nil? (dock/top-component @(:dock-stack cs))) "editor restored")
        (dock/release! cs panel-b)
        (t/is (nil? (dock/top-component @(:dock-stack cs)))
              "double release stays nil")))))

(deftest the-layout-hands-the-transcript-the-tool-registry
  (testing "the chat history the layout builds resolves a tool's own renderers
            through the app registry — the UI layer takes the lookup from its
            caller — and tool components resolve paths against the session's
            cwd, not the process's"
    (let [cwd (atom "/project/session")
          ch (layout/make-app-chat-history {:tool-display-mode :quiet} cwd
                                           tools/get-tool)
          _ (chat-history/chat-history-add-message!
             ch {:role :tool :tool-name "read" :arguments {:path "a.txt"}
                 :content "" :is-error false})
          comps (filter #(= :tool (:kind %)) (map :component @(:messages-atom ch)))
          rendered (mapv strip-ansi (mapcat #(protocols/render % 60) comps))
          ctx (var te/tool-execution-context)]
      (t/is (seq comps) "the tool message became a component")
      (t/is (some #(re-find #"read a\.txt" %) rendered)
            "the tool definition's :title rendered — the registry lookup
             reached the component")
      (t/is (= "/project/session" (:cwd (ctx (first comps) nil true)))
            "tool components resolve paths against the session's cwd"))))

(deftest test-oauth-login-dialog-covers-and-releases
  (testing "kmetia/kmet-agent#5: the :select round trip COVERS the login
            dialog with the method selector (dock/cover!) instead of
            re-mounting it, so no mount handle can go stale; closing the
            selector reveals the dialog, and the flow's release + dispose
            leaves the dock empty with the editor holding input"
    (support/install-app-keybindings!)
    (let [ui (tui/create-tui nil)
          ed (editor/make-editor)
          cs (tu/with-test-engine {:tui ui
                                   :dock-stack (atom [])
                                   :current-editor-atom (atom ed)
                                   :chat-history (chat-history/make-chat-history)})
          covers (atom 0)
          selector-p (promise)
          released-p (promise)
          _ (tui/tui-add-child ui ed)
          _ (tui/tui-set-focus-home!
             ui #(or (dock/top-focus-target (deref (:dock-stack cs)))
                     (deref (:current-editor-atom cs))))
          ;; 1 = the dialog, 2 = the method selector covering it; the
          ;; cover leaves the dialog below (depth 2), the flow's final
          ;; release empties the stack and the guard hands focus back to
          ;; the editor — waiting on the FOCUS atom (not the stack) lands
          ;; after the guard has run
          _ (add-watch (:dock-stack cs) ::issue-5-covers
                       (fn [_ _ _ new]
                         (when (= 2 (swap! covers inc))
                           (deliver selector-p {:selector (dock/top-component new)
                                                :depth (count new)}))))
          _ (add-watch (:focused-component ui) ::issue-5-released
                       (fn [_ _ _ new]
                         (when (and (identical? ed new)
                                    (empty? @(:dock-stack cs)))
                           (deliver released-p :released))))
          provider {:id :test-provider
                    :name "Test Provider"
                    :oauth {:login (fn [interaction]
                                     (let [method ((:prompt interaction)
                                                   {:type :select
                                                    :message "Select login method:"
                                                    :options [{:id "browser"
                                                               :label "Browser login"}
                                                              {:id "device"
                                                               :label "Device code login"}]})]
                                       (when-not (= "browser" method)
                                         (throw (ex-info "unexpected method"
                                                         {:method method})))
                                       {:type :oauth :access "a" :refresh "r"}))}}]
      (with-redefs [auth/set-oauth-credential! (fn [_ _] nil)]
        ((var ia/oauth-login!) cs provider)
        (let [covered (deref selector-p 5000 :timeout)]
          (t/is (not= :timeout covered) "the method selector mounted")
          (t/is (= 2 (:depth covered))
                "the selector COVERS the dialog — it stayed below instead of
                 being replaced/re-mounted (pi showAuthSelect)")
          (protocols/handle-input (:selector covered) "\r"))
        (t/is (= :released (deref released-p 5000 :timeout))
              "the flow released the dock")
        (t/is (empty? @(:dock-stack cs)) "no dialog left on screen")
        (t/is (identical? ed (tui/tui-focused-component ui))
              "the editor holds input again")))))

(defn- strip-ansi-lines [lines]
  (mapv strip-ansi lines))

(deftest test-widget-area-tracked-reactivity
  (testing "the widget strips re-derive from pure map swaps: registered
            widgets appear, removals disappear, the below strip renders
            nothing while empty, dispose unwinds cleanly"
    (let [above (atom {})
          below (atom {})
          mk (fn [label] ((var ui-registry/make-extension-widget-component) nil
                                                                            [:text {:padding-x 1 :padding-y 0} label]))
          above-root (hiccup/root ((var ui-registry/make-widget-area-above) above))
          below-root (hiccup/root ((var ui-registry/make-widget-area-below) below))]
      (try
        (swap! above assoc :w1 (mk "widget one"))
        (let [lines (strip-ansi-lines (protocols/render above-root 40))]
          (t/is (some #(re-find #"widget one" %) lines) "widget rendered"))
        ;; a pure map swap re-derives on next render (tracked read)
        (swap! above assoc :w2 (mk "widget two"))
        (let [lines (strip-ansi-lines (protocols/render above-root 40))]
          (t/is (some #(re-find #"widget two" %) lines) "second widget shown")
          (t/is (some #(re-find #"widget one" %) lines) "first still shown"))
        ;; below strip renders nothing while empty, widgets once added
        (t/is (empty? (protocols/render below-root 40)) "empty below strip")
        (reset! below {:wb (mk "below widget")})
        (let [lines (strip-ansi-lines (protocols/render below-root 40))]
          (t/is (some #(re-find #"below widget" %) lines) "below widget shown"))
        ;; removal disappears on next render
        (swap! above dissoc :w1)
        (let [lines (strip-ansi-lines (protocols/render above-root 40))]
          (t/is (not-any? #(re-find #"widget one" %) lines) "removed widget gone"))
        (finally
          (protocols/dispose above-root)
          (protocols/dispose below-root))))))

(deftest test-widget-tree-content-and-dispose
  (testing "hiccup tree content compiles to a renderable wrapper whose
            :dispose unwinds owned cleanups (the set-widget replace/remove
            hook's contract)"
    (let [cleanups (atom 0)
          cleanup-fn (fn [_props]
                       (component/with-let [_ (swap! cleanups inc)]
                         [:text {:padding-x 0 :padding-y 0} "owned"]
                         (finally (swap! cleanups dec))))
          w ((var ui-registry/make-extension-widget-component)
             nil [:container {}
                  [:text {:padding-x 1 :padding-y 0} "tree widget"]
                  [cleanup-fn {}]])]
      ;; vector content compiles to a real stamped component — spliceable
      (t/is (satisfies? protocols/IComponent w))
      (let [lines (strip-ansi-lines (protocols/render w 40))]
        (t/is (some #(re-find #"tree widget" %) lines)))
      (t/is (= 1 @cleanups) "render initialized the owned subtree")
      (protocols/dispose w)
      (t/is (= 0 @cleanups) "component dispose unwound the owned subtree"))))

(deftest test-custom-component-tree-content
  (testing "normalize-custom-component accepts a hiccup element tree for
            ui-custom dialogs — compiles to a renderable IComponent whose
            dispose unwinds owned cleanups"
    (let [cleanups (atom 0)
          cleanup-fn (fn [_props]
                       (component/with-let [_ (swap! cleanups inc)]
                         [:text {:padding-x 0 :padding-y 0} "dialog body"]
                         (finally (swap! cleanups dec))))
          comp (var-get #'ui-registry/normalize-custom-component)
          c (comp [:container {}
                   [:text {:padding-x 1 :padding-y 0} "MCP OAuth"]
                   [cleanup-fn {}]])]
      ;; NOTE: satisfies? is unreliable for reify under SCI — assert via
      ;; protocol dispatch (render/dispose), which is what the host uses
      (let [lines (strip-ansi-lines (protocols/render c 40))]
        (t/is (some #(re-find #"MCP OAuth" %) lines))
        (t/is (some #(re-find #"dialog body" %) lines)))
      (t/is (= 1 @cleanups))
      ;; the host's close/replace/shutdown sites all funnel through
      ;; dispose-dialog-component! — prove THAT path unwinds tree dialogs
      ((var-get #'ui-registry/dispose-dialog-component!) c)
      (t/is (= 0 @cleanups) "dispose unwinds on dialog close"))))

(deftest test-dispose-dialog-component-shapes
  (testing "dispose-dialog-component! (kmet.ui.tui.custom-dialog-adapter/
            dispose-component!) handles every component shape: duck-typed
            maps via their :dispose key, records via the protocol, nil
            no-op — and a throwing foreign dispose never propagates"
    (let [dispose! (var-get #'ui-registry/dispose-dialog-component!)
          called (atom 0)]
      (dispose! {:render (fn [_] ["duck"]) :dispose (fn [] (swap! called inc))})
      (t/is (= 1 @called) "duck-typed :dispose invoked")
      (dispose! {:render (fn [_] ["duck"]) :dispose (fn [] (throw (ex-info "boom" {})))})
      (t/is true "a throwing foreign dispose is swallowed")
      (dispose! {:render (fn [_] ["no dispose key"])})
      (t/is true "a map without :dispose does not propagate the dispatch error")
      (dispose! nil)
      (t/is true "nil is a no-op")
      (let [c (hiccup/compile-tree [:container {} [:text {:padding-x 0 :padding-y 0} "x"]])]
        (dispose! c)
        (t/is true "records dispatch through the protocol")))))

(deftest test-widget-string-vector-no-longer-lines
  (testing "breaking change: string vectors are NOT line lists anymore —
            they fail tree compilation loudly instead of silently rendering
            text lines"
    (t/is (thrown? Exception
                   ((var ui-registry/make-extension-widget-component) nil ["just" "lines"])))))

;; ─── Editor submit ordering (pi: submitValue clears before onSubmit) ───────

(deftest test-submit-clears-editor-before-command-dispatch
  (testing "a submitted command finds the editor already empty (pi: the
            editor clears in submitValue before onSubmit runs): a ui-custom
            dialog the command mounts saves that empty draft, so closing
            the /tools dialog cannot put the submitted '/tools' line back
            into the editor — while a dialog opened outside a submit still
            restores the draft it covered"
    (commands/clear-commands!)
    (let [ui (tui/create-tui nil)
          ed (editor/make-editor)
          ch (chat-history/make-chat-history)
          fdp-instance (fdp/make-footer-data-provider)
          cs (tu/with-test-engine {:tui ui
                                   :dock-stack (atom [])
                                   :current-editor-atom (atom ed)
                                   :editor ed
                                   :chat-history ch
                                   :footer-provider fdp-instance
                                   :status-indicator (status-indicator/make-status-indicator)
                                   :config cfg/default-config
                                   :agent-state (atom (agent/make-agent-state))
                                   :session-atom (atom nil)
                                   :running-turn? (atom false)})
          seen (atom ::unset)
          close-ref (atom nil)
          submit ((var layout/make-editor-submit-handler) cs)
          factory (fn [_tui _th _kb close]
                    (reset! close-ref close)
                    {:render (fn [_width] [""])})]
      (try
        ((var ui-registry/build-extension-ui-registry)
         {:tui ui :cs cs}
         {:ftr {:extension-statuses-atom (atom {})}
          :ed ed
          :ch ch
          :fdp fdp-instance
          :widgets-above-atom (atom {})
          :widgets-below-atom (atom {})}
         nil)
        (commands/register-command!
         {:name "tools"
          :description "test"
          :extension-handler (fn [_ctx _args]
                               (reset! seen (extensions/ui-get-editor-text))
                               (extensions/ui-custom factory {}))})
        (editor/editor-set-text! ed "/tools")
        (submit "/tools")
        (t/is (= "" @seen)
              "the command handler sees the cleared editor")
        (t/is (= 1 (count @(:dock-stack cs)))
              "the dialog mounted over the editor")
        (@close-ref nil)
        (t/is (= "" (editor/editor-get-text ed))
              "closing the dialog leaves the editor empty — no resurrected /tools")
        (t/is (empty? @(:dock-stack cs)) "the dialog left the dock")
        (testing "a dialog opened outside a submit still restores the draft"
          (editor/editor-set-text! ed "draft")
          (extensions/ui-custom factory {})
          (@close-ref nil)
          (t/is (= "draft" (editor/editor-get-text ed))))
        (finally
          (commands/clear-commands!)
          (tu/clear-extension-wiring!))))))

