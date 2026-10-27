(ns kmet.app.extensions.test-layers
  "kmet.app.extensions.layers — the extension rows of the two ambient
   composition layers: what they compose, the per-scope state the config
   screen shows, and the writes its toggles make."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kmet.app.config :as cfg]
            [kmet.app.extensions.layers :as layers]
            [kmet.app.profile :as profile]))

(defn- tmp-dir []
  (str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")})))

(defn- with-layers
  "Run F with the two layer paths in a fresh temp dir: F gets the paths."
  [f]
  (let [dir (tmp-dir)
        global (str (fs/path dir "patch.edn"))
        project (str (fs/path dir "project-patch.edn"))]
    (with-redefs [cfg/global-patch-path (fn [] global)
                  cfg/project-patch-path (fn [] project)]
      (f {:global global :project project}))))

(defn- write-layer! [path data]
  (spit path (pr-str data))
  data)

(defn- read-layer [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(deftest composes-extension-rows-and-project-overrides
  (with-layers
    (fn [{:keys [global project]}]
      (write-layer! global {:rows [{:id :ext/a :plugin :ext/a}
                                   {:id :custom :plugin :ext/b}]
                            :patch [{:id :custom :remove true}]})
      (write-layer! project {:rows [{:id :custom-project :plugin :ext/c}]
                             :patch [{:id :ext/a :remove true}
                                     {:id :ext/b :plugin :ext/b}]})
      (let [composed (layers/composition (layers/layers))
            global-sources (layers/sources-of (:global composed))
            project-sources (layers/sources-of (:project composed))]
        (is (= #{:ext/a} global-sources)
            "a later row replaces an earlier one by id, and a remove patch drops it")
        (is (= #{:ext/b :ext/c} project-sources)
            "the project layer composes on top of the global one")
        (is (= {:enabled true :override-state :inherit}
               (layers/item-state composed :ext/a :global)))
        (is (= {:enabled false :override-state :unload}
               (layers/item-state composed :ext/a :project)))
        (is (= {:enabled false :override-state :inherit}
               (layers/item-state composed :ext/b :global)))
        (is (= {:enabled true :override-state :load}
               (layers/item-state composed :ext/c :project)))
        (is (= {:enabled true :override-state :load}
               (layers/item-state composed :ext/b :project)))
        (is (= {:enabled false :override-state :inherit}
               (layers/item-state composed :ext/missing :project)))))))

(deftest global-writers-compose-and-drop-a-row
  (with-layers
    (fn [{:keys [global]}]
      (testing "enable appends a row once"
        (is (true? (layers/enable-global! :ext/a)))
        (is (= {:rows [{:id :ext/a :plugin :ext/a}], :patch []}
               (read-layer global)))
        (is (false? (layers/enable-global! :ext/a)) "already composed"))
      (testing "an existing row for the source (any id) is not duplicated"
        (fs/delete global)
        (write-layer! global {:rows [{:id :mine :plugin :ext/a}] :patch []})
        (is (false? (layers/enable-global! :ext/a)))
        (is (= 1 (count (:rows (read-layer global))))))
      (testing "a patch-insert enablement is disabled the same way"
        (fs/delete global)
        (write-layer! global {:rows []
                              :patch [{:id :ext/a :plugin :ext/a :config {:y 1}}]})
        (is (true? (layers/disable-global! :ext/a)))
        (is (empty? (layers/sources-of
                     (:global (layers/composition (layers/layers))))))
        (is (true? (layers/enable-global! :ext/a)))
        (is (= [{:id :ext/a :plugin :ext/a :config {:y 1}}]
               (:patch (read-layer global)))
            "the insert and its config survive the cycle"))
      (testing "disable keeps the row and composes a remove patch"
        (fs/delete global)
        (write-layer! global {:rows [{:id :ext/a :plugin :ext/a
                                      :config {:x 1}}]
                              :patch []})
        (is (true? (layers/disable-global! :ext/a)))
        (is (= {:rows [{:id :ext/a :plugin :ext/a :config {:x 1}}]
                :patch [{:id :ext/a :remove true}]}
               (read-layer global)))
        (is (empty? (layers/sources-of
                     (:global (layers/composition (layers/layers))))))
        (is (false? (layers/disable-global! :ext/a)) "already off")
        (testing "enable drops the remove patch and keeps the config"
          (is (true? (layers/enable-global! :ext/a)))
          (is (= {:rows [{:id :ext/a :plugin :ext/a :config {:x 1}}]
                  :patch []}
                 (read-layer global)))
          (is (= #{:ext/a}
                 (layers/sources-of
                  (:global (layers/composition (layers/layers)))))))))))

(deftest project-writers-compose-a-row-inherit-or-unload
  (with-layers
    (fn [{:keys [global project]}]
      (testing "load composes a project row when the global layer does not"
        (is (true? (layers/apply-project-state! :ext/a :load)))
        (is (= [{:id :ext/a :plugin :ext/a}]
               (:patch (read-layer project))))
        (is (= #{:ext/a}
               (layers/sources-of (:project (layers/composition (layers/layers))))))
        (is (true? (layers/apply-project-state! :ext/a :inherit)))
        (is (= [] (:patch (read-layer project)))))
      (testing "load is a no-op when the global layer already composes it"
        (write-layer! global {:rows [{:id :ext/a :plugin :ext/a}] :patch []})
        (is (false? (layers/apply-project-state! :ext/a :load)))
        (is (= [] (:patch (read-layer project))) "no project entry is written"))
      (testing "unload composes a remove patch for the global row"
        (is (true? (layers/apply-project-state! :ext/a :unload)))
        (is (= [{:id :ext/a :remove true}] (:patch (read-layer project))))
        (is (= #{}
               (layers/sources-of (:project (layers/composition (layers/layers)))))))
      (testing "a config patch on the global row survives the disable cycle"
        (fs/delete project)
        (write-layer! project {:rows [] :patch [{:id :ext/a :config {:x 1}}]})
        (is (true? (layers/apply-project-state! :ext/a :unload)))
        (is (true? (layers/apply-project-state! :ext/a :inherit)))
        (is (= [{:id :ext/a :config {:x 1}}]
               (:patch (read-layer project)))))
      (testing "unload targets the row's own id (a custom global id)"
        (fs/delete project)
        (write-layer! global {:rows [{:id :mine :plugin :ext/a}] :patch []})
        (is (true? (layers/apply-project-state! :ext/a :unload)))
        (is (= [{:id :mine :remove true}] (:patch (read-layer project))))
        (testing "inherit cleans that remove patch again"
          (is (true? (layers/apply-project-state! :ext/a :inherit)))
          (is (= [] (:patch (read-layer project)))))))))

(deftest the-written-layers-compose
  (with-layers
    (fn [{:keys [project]}]
      (testing "enable, project disable, then global disable"
        (layers/enable-global! :ext/a)
        (layers/apply-project-state! :ext/a :unload)
        (layers/disable-global! :ext/a)
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (empty? (:rows composed))
              "a project remove of a globally disabled row does not dangle")))
      (testing "a preserved project config patch is harmless while the row is off"
        (fs/delete project)
        (spit project (pr-str {:patch [{:id :ext/a :config {:x 1}}]}))
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (empty? (:rows composed))))
        (layers/enable-global! :ext/a)
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (= {:x 1} (:config (first (:rows composed))))
              "re-enabling the row applies the project config again")))
      (testing "a project load re-enables a globally disabled row"
        (layers/disable-global! :ext/a)
        (layers/apply-project-state! :ext/a :load)
        (let [parsed (layers/layers)
              composed (profile/compose [(:data (:global parsed))
                                         (:data (:project parsed))])]
          (is (= [:ext/a] (mapv :plugin (:rows composed)))))))))

(deftest a-malformed-layer-is-reported-and-never-overwritten
  (with-layers
    (fn [{:keys [global]}]
      (spit global "{:rows :nope}\n")
      (let [before (slurp global)
            read (layers/layers)]
        (is (some? (get-in read [:global :error])))
        (is (= {:rows {}}
               (:global (layers/composition read)))
            "a malformed layer composes as empty")
        (is (thrown? clojure.lang.ExceptionInfo
                     (layers/enable-global! :ext/a)))
        (is (= before (slurp global)) "the write refuses to clobber it")))))

(deftest writes-preserve-other-layer-keys
  (with-layers
    (fn [{:keys [global project]}]
      (write-layer! global {:rows [] :patch [] :note "keep me"})
      (layers/enable-global! :ext/a)
      (is (= "keep me" (:note (read-layer global))))
      (write-layer! project {:rows [] :patch [] :note "also keep me"})
      (layers/apply-project-state! :ext/a :load)
      (is (= "also keep me" (:note (read-layer project)))))))
