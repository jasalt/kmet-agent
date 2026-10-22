(ns kmet.agent.session.test-tree
  "Branches and trees: branch summaries, labels, the tree projection, and
   fork/clone."
  (:require [clojure.test :as t]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.agent.session.core :as s]
            [kmet.agent.session.test-support :as support]))

(t/use-fixtures :once support/with-clean-test-dir)

(t/deftest test-session-fork
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q"}]})
    (let [e2 (s/append-entry session {:role :assistant :content [{:type :text :text "a"}]})
          fork (s/fork-session session (:id e2))]
      (t/is (some? fork))
      (t/is (not= (:id session) (:id fork)))
      (t/is (>= (count @(:entries fork)) 1)))))

(t/deftest test-session-fork-nonexistent
  (let [session (s/create-session support/test-dir)]
    (t/is (nil? (s/fork-session session "nonexistent")))))

(t/deftest test-session-get-tree
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content [{:type :text :text "q1"}]})
    (s/append-entry session {:role :assistant :content [{:type :text :text "a1"}]})
    (s/append-entry session {:role :user :content [{:type :text :text "q2"}]})
    ;; a recorded empty completion (clean stream, zero content) — pi's tree
    ;; selector labels it "(no content)" (getEntryDisplayText)
    (s/append-entry session {:role :assistant :content []})
    (let [tree (s/get-tree session)]
      (t/is (vector? tree))
      (t/is (pos? (count tree)))
      (t/is (= :user (:role (first tree)))
            "Root is the first entry (user)")
      (t/is (= 1 (count (:children (first tree))))
            "Root has one child")
      (t/is (= :assistant (:role (first (:children (first tree)))))
            "First child is assistant")
      ;; linear chain q1 → a1 → q2 → empty assistant
      (let [empty-node (-> tree first :children first :children first :children first)]
        (t/is (= :assistant (:role empty-node)))
        (t/is (= "(no content)" (:summary empty-node))
              "empty assistant entries are labeled '(no content)' (pi)")))))

(t/deftest test-session-get-tree-large-session
  ;; get-tree builds correct structure at scale: a long chain plus a
  ;; fan-out branch (regression guard for the per-parent full scan this
  ;; replaced — group-by keeps construction linear in entry count).
  ;; replace-entries! builds the chain in one publication: the append path
  ;; would write 2000 lines to disk, which is setup cost, not behavior.
  (let [session (s/create-session support/test-dir)
        n 2000]
    (s/replace-entries! session
                        (mapv (fn [i]
                                {:role (if (even? i) :user :assistant)
                                 :content [{:type :text :text (str "m" i)}]})
                              (range n)))
    (s/branch! session (:id (first @(:entries session))))
    (s/append-entry session {:role :user :content [{:type :text :text "fork"}]})
    ;; iterative: the chain is 2000 deep, a recursive walk overflows
    (letfn [(depth [root]
              (loop [stack [[root 1]]
                     deepest 0]
                (if-let [[node d] (peek stack)]
                  (recur (into (pop stack) (map (fn [c] [c (inc d)]) (:children node)))
                         (max deepest d))
                  deepest)))]
      (let [tree (s/get-tree session)
            root (first tree)]
        (t/is (= 1 (count tree)) "single root")
        (t/is (= n (depth root)) "chain covers every appended entry")
        (t/is (= 2 (count (:children root))) "branched entry fans out to 2 children")
        (t/is (= "fork" (-> root :children peek :summary str/trim))
              "branch sibling keeps append order after the chain child")))))

;; ─── Branch summaries (G17): branch-with-summary! ─────────────────────────

(t/deftest test-branch-with-summary
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          summary (s/branch-with-summary! session (:id u2)
                                          "explored option B" {:details {:x 1}})]
      (t/is (= :branch-summary (:role summary)))
      (t/is (= (:id u2) (:from-id summary)))
      (t/is (= (:id u2) (:parent-id summary))
            "summary entry is a child of the new leaf position")
      (t/is (= "explored option B" (:summary summary)))
      (t/is (= {:x 1} (:details summary)))
      (t/is (= (:id summary) @(:leaf-id session))))
    ;; nil branch-from-id → root, from-id "root"
    (s/reset-leaf! session)
    (let [summary (s/branch-with-summary! session nil "back to root")]
      (t/is (= "root" (:from-id summary)))
      (t/is (nil? (:parent-id summary))))
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (s/branch-with-summary! session "nope" "x")))))

(t/deftest test-branch-summary-projects-to-context
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [u2 (s/append-entry session {:role :user :content "q2"})
          _ (s/branch-with-summary! session (:id u2) "abandoned path")
          msgs (mapcat s/context-messages (s/build-context session))]
      (t/is (= :branch-summary (:role (last msgs))))
      (t/is (= "abandoned path" (:summary (last msgs)))
            "branch_summary keeps its role + summary (pi: branchSummary)"))))

(t/deftest test-branch-summary-entries
  ;; branch: root → u1 → a1 → u2 → a2; navigate from a2 back to u1
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [a1 (s/append-entry session {:role :assistant :content "a1"})
          u2 (s/append-entry session {:role :user :content "q2"})
          a2 (s/append-entry session {:role :assistant :content "a2"})]
      (t/is (= [a2] (s/branch-summary-entries session (:id a2) (:id u2)))
            "abandoned tail from the old leaf down to (excluding) the common ancestor")
      (t/is (= [u2 a2] (s/branch-summary-entries session (:id a2) (:id a1)))
            "chronological order (rootward first), common ancestor (a1) excluded"))))

;; ─── Labels (G11): set-label!/get-label ────────────────────────────────────

(t/deftest test-labels
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q1"})
    (let [a1 (s/append-entry session {:role :assistant :content "a1"})]
      (t/is (nil? (s/get-label session (:id a1))))
      (s/set-label! session (:id a1) "fix-123")
      (t/is (= "fix-123" (s/get-label session (:id a1))))
      ;; latest-wins
      (s/set-label! session (:id a1) "fix-456")
      (t/is (= "fix-456" (s/get-label session (:id a1))))
      ;; clearing
      (s/set-label! session (:id a1) nil)
      (t/is (nil? (s/get-label session (:id a1))))
      (t/is (thrown? clojure.lang.ExceptionInfo (s/set-label! session "nope" "x"))))))

;; ─── Fork (G18): createBranchedSession semantics ───────────────────────────

(t/deftest test-fork-keeps-ids-and-parent-chain
  (let [session (s/create-session support/test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        a1 (s/append-entry session {:role :assistant :content "a1"})
        u2 (s/append-entry session {:role :user :content "q2"})]
    (s/branch! session (:id u2))
    (let [fork (s/fork-session session (:id u2))]
      (t/is (some? fork))
      (t/is (= [(:id q1) (:id a1) (:id u2)] (map :id (s/get-branch fork)))
            "fork branch = root→entry")
      (t/is (= (:id u2) (:id (last (s/get-branch fork))))
            "entry ids are preserved")
      (t/is (= (:file session) (get-in fork [:header :parent-session]))
            "fork header links the source file")
      (t/is (= (get-in session [:header :cwd]) (get-in fork [:header :cwd]))
            "fork keeps the source cwd")
      (t/is (= (:id u2) (:parent-id (s/append-entry fork {:role :assistant :content "a3"})))
            "fork's next append children off the preserved leaf id"))))

(t/deftest test-fork-rechains-around-labels
  (let [session (s/create-session support/test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        a1 (s/append-entry session {:role :assistant :content "a1"})
        ;; label entry chains off a1, then the next message chains off the label
        lab (s/set-label! session (:id a1) "keep")
        u2 (s/append-entry session {:role :user :content "q2"})
        a2 (s/append-entry session {:role :assistant :content "a2"})]
    (t/is (= (:id lab) (:parent-id u2))
          "later entries are children of the label entry")
    (let [fork (s/fork-session session (:id a2))
          fork-u2 (s/get-entry fork (:id u2))
          fork-a2 (s/get-entry fork (:id a2))
          fork-leaf (last (s/get-branch fork))]
      (t/is (= [(:id q1) (:id a1) (:id u2) (:id a2)]
               (mapv :id (butlast (s/get-branch fork))))
            "fork path skips labels and re-chains parents")
      (t/is (= :label (:role fork-leaf))
            "the recreated label chains off the last retained entry (the new leaf)")
      (t/is (= (:id a1) (:target-id fork-leaf)))
      (t/is (= (:id a1) (:parent-id fork-u2))
            "re-chained: u2's parent is a1, not the removed label")
      (t/is (= (:id u2) (:parent-id fork-a2))
            "a2's parent chain is intact")
      (t/is (= "keep" (s/get-label fork (:id a1)))
            "label recreated in the fork (original target, latest label)")
      (t/is (= (:timestamp lab) (:timestamp fork-leaf))
            "recreated label entry keeps its original timestamp")
      (t/is (= 5 (count @(:entries fork)))
            "fork holds the four retained entries + the recreated label"))))

(t/deftest test-clone-session
  (let [session (s/create-session support/test-dir)]
    (t/is (nil? (s/clone-session session))
          "nothing to clone before the first entry")
    (s/append-entry session {:role :user :content "q1"})
    (s/append-entry session {:role :assistant :content "a1"})
    (let [clone (s/clone-session session)]
      (t/is (some? clone))
      (t/is (= (map :id (s/get-branch session))
               (map :id (s/get-branch clone)))
            "clone copies the full active branch")
      (t/is (= (:file session) (get-in clone [:header :parent-session]))))))

(t/deftest test-fork-from
  (let [source (s/create-session support/test-dir)]
    (s/append-entry source {:role :user :content "q1"})
    (s/append-entry source {:role :assistant :content "a1"})
    (let [path (:file source)
          ;; force persistence (lazy G4 writes on first assistant message)
          _ (t/is (fs/exists? path))
          target-cwd (str (fs/path (fs/temp-dir) "other-proj"))
          fork (s/fork-from path target-cwd support/test-dir)]
      (t/is (some? fork))
      (t/is (= (map :id (s/get-branch source))
               (map :id (s/get-branch fork)))
            "fork-from copies all entries verbatim (ids preserved)")
      (t/is (= target-cwd (get-in fork [:header :cwd]))
            "fork-from header carries the target cwd")
      (t/is (= path (get-in fork [:header :parent-session])))
      (t/is (fs/exists? (:file fork))
            "fork-from writes the fork file immediately")))
  (let [empty-file (str support/test-dir "/empty.ednl")]
    (spit empty-file "")
    (t/is (thrown? clojure.lang.ExceptionInfo
                   (s/fork-from empty-file (str (fs/temp-dir)) support/test-dir)))
    (fs/delete empty-file)))

(t/deftest test-common-ancestor-id
  (let [session (s/create-session support/test-dir)
        q1 (s/append-entry session {:role :user :content "q1"})
        _ (s/append-entry session {:role :assistant :content "a1"})
        u2 (s/append-entry session {:role :user :content "q2"})
        a2 (s/append-entry session {:role :assistant :content "a2"})]
    (t/is (= (:id q1) (s/common-ancestor-id session (:id a2) (:id q1))))
    (t/is (= (:id u2) (s/common-ancestor-id session (:id a2) (:id u2))))
    (t/is (= (:id a2) (s/common-ancestor-id session (:id a2) (:id a2)))
          "same entry is its own common ancestor")
    (t/is (nil? (s/common-ancestor-id session nil (:id a2)))
          "no old leaf → nil")))

(t/deftest test-tree-displays-change-entries
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-model-change! session :anthropic "claude-sonnet")
    (s/append-thinking-level-change! session :high)
    (letfn [(flatten-tree [nodes]
              (mapcat (fn [n]
                        (cons (:summary n) (flatten-tree (:children n))))
                      nodes))]
      (t/is (= ["q" "[model: anthropic/claude-sonnet]" "[thinking: high]"]
               (vec (flatten-tree (s/get-tree session))))
            "tree shows the switch instead of (empty)"))))

(t/deftest test-tree-displays-custom-entries
  (let [session (s/create-session support/test-dir)]
    (s/append-entry session {:role :user :content "q"})
    (s/append-custom-entry! session :my-state {:v 1})
    (s/append-custom-message-entry! session :note "hi" true)
    (s/append-custom-message-entry! session :quiet nil false)
    (letfn [(flatten-tree [nodes]
              (mapcat (fn [n]
                        (cons (:summary n) (flatten-tree (:children n))))
                      nodes))]
      (t/is (= ["q" "[custom: my-state]" "hi" "[custom: quiet]"]
               (vec (flatten-tree (s/get-tree session))))
            "tree shows custom entries instead of (empty)"))))
