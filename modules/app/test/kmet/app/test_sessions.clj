(ns kmet.app.test-sessions
  "The `:sessions` service: waiting for its storage backend, provided once over
   it, and answering from the id index that makes a created log findable again."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.sessions :as sessions]
            [kmet.srce.core :as srce]))

(defn- persistence-row
  "A `:sessionPersistence` row over the in-process backend, provided into the
   mount site's scope — what `kmet.agent.session.backend/ednl`'s row does for
   real files."
  []
  {:name :sessionPersistence
   :provide #{:sessionPersistence}
   :apply (fn [ctx _]
            (srce/provide! ctx :sessionPersistence (sessions/memory-backend)
                           {:in (:parent ctx)}))})

(deftest the-log-waits-for-its-storage-backend
  (let [ctx (srce/root)
        log (srce/plugin! ctx sessions/plugin)]
    (is (= :pending (:state @log)) "no backend yet, so the log is not mounted")
    (is (= #{:sessionPersistence} (:waiting-for @log))
        "and it says what it is waiting for")
    (is (nil? (srce/get ctx :sessions)) "nothing is published while pending")
    (let [storage (srce/plugin! ctx (persistence-row))]
      (is (= :active (:state @log)) "the backend mounting activates the log")
      (is (= :active (:state @storage)))
      (is (some? (srce/get ctx :sessions)) "and the service appears")
      (srce/dispose! storage)
      (is (= :pending (:state @log)) "losing the backend suspends it again"))))

(deftest a-created-session-is-findable-by-id
  (let [ctx (srce/root)]
    (srce/plugin! ctx (persistence-row))
    (srce/plugin! ctx sessions/plugin)
    (let [log (srce/get ctx :sessions)
          s ((:create! log) {:dir "mem" :cwd "/work"})
          id (:id ((:header log) s))]
      (is (identical? s ((:get log) id)) "the index answers with the session it minted")
      (is (nil? ((:get log) "no-such-session")))
      (testing "appends go through to the backend"
        ((:append! log) s {:role :assistant :content []})
        (is (= 1 (count ((:entries log) s)))))
      (testing "delete! also drops the index entry"
        ((:delete! log) s)
        (is (nil? ((:get log) id)))
        (is (= [] ((:paths log) "mem")))))))

(deftest a-loaded-session-joins-the-index
  (let [ctx (srce/root)]
    (srce/plugin! ctx (persistence-row))
    (srce/plugin! ctx sessions/plugin)
    (let [log (srce/get ctx :sessions)
          s ((:create! log) {:dir "mem" :cwd "/work"})]
      ((:append! log) s {:role :assistant :content []})
      (let [loaded ((:load log) (first ((:paths log) "mem")))]
        (is (= (:id ((:header log) s)) (:id ((:header log) loaded))))
        (is (identical? loaded ((:get log) (:id ((:header log) loaded))))
            "a resumed session is reachable by id like a created one")))))

(deftest a-row-below-sees-the-service
  (let [ctx (srce/root)]
    (srce/plugin! ctx (persistence-row))
    (srce/plugin! ctx sessions/plugin)
    (let [log (srce/get ctx :sessions)
          sibling (srce/plugin! ctx {:name :consumer
                                     :inject {:sessions nil}
                                     :apply (fn [c _] (srce/provide! c :seen (srce/get c :sessions)))})
          child (srce/extend ctx {:name :agent/1})]
      (is (= :active (:state @sibling)) "the inject gate is satisfied")
      (is (identical? log (srce/get child :sessions)) "a child ctx reads it through the chain")
      (srce/dispose! sibling))))

(deftest disposing-the-row-removes-the-service
  (let [ctx (srce/root)]
    (srce/plugin! ctx (persistence-row))
    (let [log (srce/plugin! ctx sessions/plugin)]
      (is (some? (srce/get ctx :sessions)))
      (srce/dispose! log)
      (is (nil? (srce/get ctx :sessions)) "the provide! entry was the row's effect"))))
