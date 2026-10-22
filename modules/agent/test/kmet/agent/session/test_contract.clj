(ns kmet.agent.session.test-contract
  "The `:sessionPersistence` contract, run against both backends: kmet2's
   in-process `memory-backend` and the EDNL files kmet1's store owns. One set of
   assertions, so the storage seam cannot drift from the store it will replace
   (`new-app.md`, landing 4a).

   Each backend gets a fresh session directory, so one case's files never leak
   into the next."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [kmet.agent.session.backend :as session-backend]
            [kmet.app.sessions :as sessions]))

(defn- backends
  "`[kind backend-value dir]` per backend — the backend, plus the session
   directory its `create!` writes under."
  []
  (let [dir-of #(str (fs/create-temp-dir {:dir (System/getenv "TMPDIR")}))
        mem-dir (dir-of)
        ednl-dir (dir-of)]
    [["memory" (sessions/memory-backend) mem-dir]
     ["ednl" (session-backend/ednl) ednl-dir]]))

(def ^:private user-entry {:role :user :content [{:type :text :text "hello"}]})
(def ^:private assistant-entry {:role :assistant :content [{:type :text :text "hi"}]})

(deftest create-gives-a-header-and-no-entries
  (doseq [[kind backend dir] (backends)]
    (testing kind
      (let [s ((:create! backend) {:dir dir :cwd dir})
            header ((:header backend) s)]
        (is (= :session (:type header)) "the header says what the file is")
        (is (= 1 (:version header)) "the format version is on the header")
        (is (= (:id header) (:id s)) "the handle's id is the header's id")
        (is (seq (:id header)))
        (is (seq (:created-at header)))
        (is (= dir (:cwd header)) "a session carries the cwd it belongs to")
        (is (= [] ((:entries backend) s)) "a new session has no entries")))))

(deftest append-stamps-and-returns-the-entry
  (doseq [[kind backend dir] (backends)]
    (testing kind
      (let [s ((:create! backend) {:dir dir :cwd dir})
            e ((:append! backend) s user-entry)]
        (is (map? e))
        (is (seq (:id e)) "the backend mints the entry id")
        (is (nil? (:parent-id e)) "the first entry is a root")
        (is (seq (:timestamp e)))
        (is (= "hello" (-> e :content first :text))
            "the caller's fields survive the stamp")
        (is (= [e] ((:entries backend) s))
            "the append is the write: reading returns what was appended")))))

(deftest a-second-append-chains-onto-the-first
  (doseq [[kind backend dir] (backends)]
    (testing kind
      (let [s ((:create! backend) {:dir dir :cwd dir})
            a ((:append! backend) s user-entry)
            b ((:append! backend) s assistant-entry)]
        (is (= (:id a) (:parent-id b)) "the leaf moved to the first entry")
        (is (= [a b] ((:entries backend) s)) "append order is reading order")
        (is (= 2 (count (distinct [(:id a) (:id b)]))) "ids are unique")))))

(deftest nothing-is-stored-before-the-first-assistant-entry
  (doseq [[kind backend dir] (backends)]
    (testing kind
      (let [s ((:create! backend) {:dir dir :cwd dir})]
        (is (= [] ((:paths backend) dir))
            "a created session that never got an answer leaves nothing")
        ((:append! backend) s user-entry)
        (is (= [] ((:paths backend) dir))
            "a user entry alone is still memory-only")
        ((:append! backend) s assistant-entry)
        (is (= 1 (count ((:paths backend) dir)))
            "the first assistant entry stores the session")))))

(deftest a-stored-session-loads-back
  (doseq [[kind backend dir] (backends)]
    (testing kind
      (let [s ((:create! backend) {:dir dir :cwd dir})
            a ((:append! backend) s user-entry)
            b ((:append! backend) s assistant-entry)
            path (first ((:paths backend) dir))
            loaded ((:load backend) path)]
        (is (seq path))
        (is (= (:id ((:header backend) s)) (:id ((:header backend) loaded)))
            "loading resolves the same session")
        (is (= [a b] ((:entries backend) loaded)) "the entries round-trip")
        (is (= dir (:cwd ((:header backend) loaded))))))))

(deftest delete-drops-the-stored-log
  (doseq [[kind backend dir] (backends)]
    (testing kind
      (let [s ((:create! backend) {:dir dir :cwd dir})]
        ((:append! backend) s assistant-entry)
        (is (= 1 (count ((:paths backend) dir))))
        (is (nil? ((:delete! backend) s)) "delete reports nothing, it just deletes")
        (is (= [] ((:paths backend) dir)) "the session is gone from the listing")))))

(deftest an-incomplete-backend-is-refused-at-construction
  (is (thrown-with-msg? Exception #"missing.*:entries"
                        (sessions/service (dissoc (sessions/memory-backend) :entries)))
      "a backend missing an op names it instead of failing mid-turn"))
