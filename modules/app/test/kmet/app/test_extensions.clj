(ns kmet.app.test-extensions
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [kmet.agent.commands :as commands]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.registry :as registry]
            [kmet.agent.tools.registry :as tools]
            [kmet.app.extensions :as row]
            [kmet.app.registries :as registries]
            [kmet.srce.core :as srce]))

(defn- with-restored-host
  "Run F with the host unbound, restoring whatever was bound before: the
   binding is process-wide and one owner at a time, so a test that leaves it
   unbound hands the next owner a host that refuses to work."
  [f]
  (let [previous (extensions/bind-services! nil)]
    (try (f)
         (finally (extensions/bind-services! previous)))))

(defn- mounted
  "A root with the host's row mounted, as the composition mounts it."
  []
  (let [root (srce/root {:name :app})
        fiber (srce/plugin! root row/plugin)]
    {:root root :fiber fiber}))

(deftest the-row-binds-the-host
  (with-restored-host
    (fn []
      (mounted)
      (is (= [] (extensions/get-tool-call-hooks)) "the host answers: no hooks yet")
      ;; the host's own tags land in the handle this row made — the thing that
      ;; makes the binding worth a row, since a run's hooks resolve here
      (extensions/register-tool-call-hook! :owner/fn (fn [_] nil))
      (is (= 1 (count (extensions/get-tool-call-hooks)))
          "a registration the host accepted is one its bound handle holds")
      (is (fn? (:register! (extensions/resources :skills)))
          "and the row binds the resource operations itself, backed by the
           composition's registries — a kmet2 process has no base binding to
           inherit them from"))))

(deftest a-second-composition-puts-the-first-binding-back
  ;; The binding is process-wide and one owner at a time: mounting replaces it,
  ;; disposing puts back what was there, so a composition that goes away cannot
  ;; leave the host pointing at a root nothing owns.
  (with-restored-host
    (fn []
      (let [{:keys [fiber]} (mounted)]
        (extensions/register-tool-call-hook! :owner/first (fn [_] nil))
        (let [{second :fiber} (mounted)]
          (is (= [] (extensions/get-tool-call-hooks))
              "the second mount binds its own handle")
          (srce/dispose! second)
          (is (= 1 (count (extensions/get-tool-call-hooks)))
              "and disposing it restores the one it replaced"))
        (srce/dispose! fiber))
      (is (= :kmet/no-host-services
             (try (extensions/get-tool-call-hooks)
                  nil
                  (catch Exception e (:type (ex-data e)))))
          "disposing the last one unbinds: a host with no owner is a named error"))))

(deftest an-extensions-command-is-the-compositions-command
  ;; 5l-ii's publisher gate: the row publishes the composition's registry as the
  ;; process handle and binds the resource operations the host calls, so a
  ;; contribution made through the host is the `:commands` service the run reads
  ;; — with nothing bound before the row (a kmet2 process), and with a base
  ;; binding (kmet1's process host) replaced, then restored on dispose.
  (with-restored-host
    (fn []
      (let [root (srce/root {:name :app})
            commands-fiber (srce/plugin! root registries/commands)
            fiber (srce/plugin! root row/plugin)]
        (try
          (is (fn? (extensions/host-binding)) "the composition owns the host")
          ((:register! (extensions/resources :commands))
           {:name "gate-cmd" :description "d" :handler (fn [_ _] nil)}
           [:app :test-owner])
          (is (some? (registry/entry (srce/get root :commands)
                                     :kmet/command "gate-cmd"))
              "the contribution lands in the composition's :commands service")
          (is (some? (commands/find-command "gate-cmd"))
              "and a reader with no ctx sees it through the published handle")
          (srce/dispose! fiber)
          (is (nil? (extensions/host-binding))
              "disposing the row puts back the binding it replaced")
          (is (nil? (commands/find-command "gate-cmd"))
              "and the shared reader no longer answers from that composition")
          (finally
            (srce/dispose! commands-fiber)))))))

(def ^:private probe-extension
  "A single-file extension whose only contribution is a tool, so the test needs
   no resource registries and exercises the tool path alone."
  (str "(ns tool-route-probe\n"
       "  (:require [kmet.extension :as ext]))\n\n"
       "(defn init [api]\n"
       "  (ext/register-tool! api {:name \"tool-route-probe\"\n"
       "                           :description \"probe\"\n"
       "                           :params {:x {:type :string}}\n"
       "                           :execute (fn [args] {:content (str (:x args))})}))\n"))

(deftest extension-tools-reach-the-compositions-tool-table
  ;; The host's :registry is a tag router: :kmet/tool (and :kmet/tool-source)
  ;; go to the composition's :tools service, which is what the loop's table,
  ;; the /tools selector and run_code read. Held in the row's own handle they
  ;; would register fine and be invisible — the Clojure extension's edit/eval
  ;; tools did exactly that.
  (with-restored-host
    (fn []
      (let [root (srce/root {:name :app})
            ;; the composition's order: the host row mounts before :tools, so
            ;; the route has to resolve the service per call, not at mount
            ext-fiber (srce/plugin! root row/plugin)
            tools-fiber (srce/plugin! root registries/tools)
            dir "target/test-ext-tool-route"
            path (str dir "/probe_ext.clj")]
        (try
          (fs/delete-tree dir)
          (fs/create-dirs dir)
          (spit path probe-extension)
          (let [result (extensions/load-extension! path)
                handle (srce/get root :tools)]
            (is (nil? (:error result)) (str "loaded: " (:error result)))
            (is (some? (tools/get-tool handle "tool-route-probe"))
                "the extension's tool is in the composition's :tools service")
            (extensions/unload-all-extensions!)
            (is (nil? (tools/get-tool handle "tool-route-probe"))
                "unload sweeps it out of the same service"))
          (finally
            (extensions/unload-all-extensions!)
            (srce/dispose! ext-fiber)
            (srce/dispose! tools-fiber)))))))
