(ns kmet.app.test-session-runtime
  "Tests for kmet.app.session-runtime: the engine front ends drive
   (engine.md §4.1, step 1)."
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest is]]
            [kmet.app.extensions :as extensions]
            [kmet.app.loop :as agent]
            [kmet.app.session :as session]
            [kmet.app.session-runtime :as rt]))

(defn- opts
  "A sparse run config — every derived option falls back to its default."
  []
  {:config {:provider :opencode-go :model "deepseek-v4-flash"}})

(defn- with-engine
  "Bootstrap an engine over a fresh temp session and call F with it,
   disposing it and removing the temp dir afterwards."
  [f]
  (let [dir (str "target/test-session-runtime-" (System/currentTimeMillis))
        sess (session/create-session dir {:cwd (str (fs/cwd))})]
    (try
      (let [engine (rt/bootstrap (:config (opts)) {:session sess})]
        (try (f engine sess)
             (finally (rt/dispose! engine))))
      (finally (fs/delete-tree dir)))))

(deftest test-bootstrap-builds-the-engine
  (with-engine
    (fn [engine sess]
      (let [ag (rt/agent-state engine)]
        (is (identical? sess (rt/session engine)) "the session is the resolved record")
        (is (identical? sess (:session ag)) "the agent runs on the same session")
        (is (= :opencode-go @(:provider ag)))
        (is (= "deepseek-v4-flash" @(:model ag)))
        (is (string? @(:system ag)) "the system prompt was built")
        (is (fn? (:on-event ag)) "the agent's UI callback is the subscriber dispatcher")
        (is (= [] (rt/scoped-models engine)))
        (is (= :off @(:thinking ag)) "thinking defaults to off")
        (is (identical? sess (extensions/get-session))
            "the extension session handle is installed")))))

(deftest test-subscribe-receives-and-unsubscribes
  (with-engine
    (fn [engine _]
      (let [events (atom [])
            unsub (rt/subscribe! engine #(swap! events conj %))]
        (rt/cancel! engine)
        (is (seq @events) "the subscriber receives the stream")
        (is (= :queue-update (:type (first @events))))
        (reset! events [])
        (unsub)
        (rt/cancel! engine)
        (is (empty? @events) "an unsubscribed fn receives nothing")))))

(deftest test-subscriber-errors-are-isolated
  (with-engine
    (fn [engine _]
      (let [events (atom [])]
        (rt/subscribe! engine (fn [_] (throw (ex-info "boom" {}))))
        (rt/subscribe! engine #(swap! events conj %))
        (is (nil? (rt/cancel! engine))
            "a throwing subscriber cannot escape into the loop")
        (is (seq @events) "the other subscribers still receive the event")))))

(deftest test-prompt-passes-through-and-records-the-run
  (with-engine
    (fn [engine _]
      (let [seen (atom nil)
            run (future :settled)
            done (fn [_] nil)]
        (with-redefs [agent/run-agent-turn
                      (fn [ag run-opts]
                        (reset! seen {:ag ag :opts run-opts})
                        run)]
          (is (nil? (rt/prompt! engine {:message "hi" :on-done done}))
              "prompt! returns immediately (completion is :agent-settled)")
          (is (identical? (rt/agent-state engine) (:ag @seen)))
          (is (= "hi" (:message (:opts @seen))))
          (is (identical? done (:on-done (:opts @seen))) "callbacks pass through")
          (is (identical? run @(:last-run engine)) "the run is recorded for dispose!"))))))

(deftest test-prompt-uses-the-live-agent-after-a-session-switch
  ;; a session switch replaces the agent state in the shared atom; prompt!
  ;; must run the new record, not the bootstrap one
  (with-engine
    (fn [engine _]
      (let [seen (atom nil)]
        (reset! (:agent-state engine)
                (assoc (rt/agent-state engine) :session ::switched))
        (with-redefs [agent/run-agent-turn (fn [ag _]
                                             (reset! seen (:session ag))
                                             (future))]
          (rt/prompt! engine {:message "hi"})
          (is (= ::switched @seen)))))))

(deftest test-dispose-clears-subscribers-and-wiring
  (with-engine
    (fn [engine _]
      (let [events (atom [])]
        (rt/subscribe! engine #(swap! events conj %))
        (is (nil? (rt/dispose! engine)))
        (is (nil? (extensions/get-session)) "the extension session handle is cleared")
        (is (nil? (rt/dispose! engine)) "dispose! is idempotent")
        (rt/cancel! engine)
        (is (empty? @events))))))

(deftest test-snapshot-is-plain-data
  (with-engine
    (fn [engine sess]
      (let [snap (rt/snapshot engine)]
        (is (identical? sess (:session snap)))
        (is (= :idle (:status snap)))
        (is (= :opencode-go (:provider snap)))
        (is (= "deepseek-v4-flash" (:model snap)))
        (is (= {:steering [] :follow-up []} (:queues snap)))
        (is (= :completed (:last-outcome snap)))
        (is (not (contains? snap :subscribers)) "the snapshot is a view, not the engine")))))
