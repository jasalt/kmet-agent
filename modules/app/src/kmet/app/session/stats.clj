(ns kmet.app.session.stats
  "Session statistics: usage and token attribution, message counts, prompt
   history, last activity, and the per-session summary. Read-only over
   kmet.app.session.store and kmet.app.session.context."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [kmet.app.session.context :as context]
            [kmet.app.session.store :as store]
            [kmet.ai.usage :as usage]))

(defn tool-usage
  "Estimated tool-result tokens per tool over the session:
   {tool-name {:calls n :tokens t}, :total {:calls n :tokens t}}. Derived
   from the entries' result content — :result-tokens (stamped by
   append-entry) when present, else estimated on the spot, so loaded,
   forked, and context-replaced sessions report too. Tool names are strings;
   \"unknown\" covers a malformed entry without :tool-name."
  [session]
  (let [grouped (reduce (fn [acc e]
                          (if (= :tool (:role e))
                            (let [tokens (long (or (:result-tokens e)
                                                   (store/estimate-result-tokens e)))
                                  tool (or (:tool-name e) "unknown")]
                              (-> acc
                                  (update-in [tool :calls] (fnil inc 0))
                                  (update-in [tool :tokens] (fnil + 0) tokens)))
                            acc))
                        {}
                        @(:entries session))]
    (assoc grouped :total
           {:calls (reduce + 0 (map :calls (vals grouped)))
            :tokens (reduce + 0 (map :tokens (vals grouped)))})))

(defn tool-usage-report
  "Per-tool token summary, highest first, with a TOTAL line — for kmet-debug.log
   and inspection (/session formats the same numbers via tool-usage)."
  [session]
  (let [{:keys [total] :as usage} (tool-usage session)]
    (if (zero? (:calls total 0))
      "no tool results"
      (str (str/join "\n"
                     (for [[tool stats] (sort-by (comp - :tokens val)
                                                 (dissoc usage :total))]
                       (format "  %-10s calls=%-4d tokens=%d"
                               (name tool) (:calls stats) (:tokens stats))))
           (format "\n  %-10s calls=%-4d tokens=%d"
                   "TOTAL" (:calls total) (:tokens total))))))

(defn usage-totals
  "Sum normalized usage (incl. USD :cost) across session entries carrying
   :usage (pi: FooterComponent accumulates usage from all session entries)."
  [session]
  (reduce (fn [totals e]
            (if-let [u (usage/entry-usage (:usage e))]
              (merge-with + totals u)
              totals))
          {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}
          @(:entries session)))

;; ─── Convenience ───────────────────────────────────────────────────────────

;; ─── Convenience ───────────────────────────────────────────────────────────

(defn get-first-message
  "First user message text of the session (pi: buildSessionInfo firstMessage
   — the first user message with text content, else \"(no messages)\")."
  [session]
  (or (some (fn [e]
              (when (= :user (:role e))
                (let [t (store/session-entry-text e)]
                  (when (seq t) t))))
            @(:entries session))
      "(no messages)"))

(defn get-prompt-history
  "User message texts of the session's active branch (root→leaf,
  compaction-aware — pi: buildContextEntries), matching the editor's
  prompt-history convention (pi: addToHistory — empty messages skipped,
  consecutive duplicates collapsed, newest 100 kept). Used to repopulate
  the editor's Up/Down history when resuming a session; kmet sessions are
  append-only, so the history is reconstituted from :user entries rather
  than stored separately."
  [session]
  (loop [ts (->> (context/context-entries (store/get-branch session))
                 (filter #(= :user (:role %)))
                 (keep (fn [e] (let [t (store/session-entry-text e)] (when (seq t) t)))))
         acc []]
    (if-let [t (first ts)]
      (recur (rest ts)
             (if (= t (peek acc))
               acc
               (let [acc (conj acc t)]
                 (if (> (count acc) 100) (subvec acc 1) acc))))
      acc)))

(defn get-message-count
  "Number of message entries in the session (pi: buildSessionInfo
   messageCount — message entries only). kmet's :bash role is the EDN
   analogue of pi's tool messages (which ARE message entries in pi), so
   :bash counts; display-only :info entries don't (pi: custom_message)."
  [session]
  (count (filter store/message-entry? @(:entries session))))

(defn get-session-stats
  "Session statistics for /session (pi: getSessionStats). Aggregates over
   ALL entries (including history compacted away), so token/cost totals
   reflect what was actually billed. Message roles: :user counts user,
   :assistant counts assistant, :tool and :bash count tool results (pi
   stores bash results as tool messages in message entries). Tool calls are
   the :tool-calls vectors on assistant entries. Tokens/cost come from
   usage-totals (assistant + tool usage, incl. compaction/branch-summary
   usage carried on entries). Returns {:file :id :user-messages
   :assistant-messages :tool-calls :tool-results :total-messages :tokens
   {:input :output :cache-read :cache-write :total} :cost}."
  [session]
  (let [{:keys [input output cache-read cache-write cost]} (usage-totals session)
        counts (reduce (fn [acc e]
                         (case (:role e)
                           :user (update acc :user inc)
                           :assistant (update acc :assistant inc)
                           (:tool :bash) (update acc :tool-results inc)
                           acc))
                       {:user 0 :assistant 0 :tool-results 0}
                       @(:entries session))
        tool-calls (reduce (fn [n e]
                             (if (= :assistant (:role e))
                               (+ n (count (:tool-calls e)))
                               n))
                           0
                           @(:entries session))]
    {:file (:file session)
     :id (:id session)
     :user-messages (:user counts)
     :assistant-messages (:assistant counts)
     :tool-calls tool-calls
     :tool-results (:tool-results counts)
     :total-messages (get-message-count session)
     :tokens {:input (long input) :output (long output) :cache-read (long cache-read) :cache-write (long cache-write)
              :total (+ input output cache-read cache-write)}
     :cost (double cost)}))

(defn get-last-assistant-text
  "Text content of the last assistant message on the branch (pi:
   getLastAssistantText — /copy). Scans the branch (not the full entry
   list, matching pi's live messages) for the last assistant entry with
   non-empty text; aborted/empty assistant entries (no text blocks) are
   skipped. Returns the trimmed text or nil."
  [session]
  (some (fn [e]
          (when (= :assistant (:role e))
            (let [t (store/session-entry-text e)]
              (when (seq t) t))))
        (reverse (store/get-branch session))))

(defn- provider-model-key
  "\"provider/model\" attribution key for an entry carrying :provider/:model
   (the model id may itself contain slashes — pi keys the same way)."
  [entry]
  (str (name (:provider entry)) "/" (:model entry)))

(defn usage-breakdown
  "Per-model cost/token attribution for /session (pi: getUsageCostBreakdown).
   An assistant entry is attributed to its own :provider/:model stamp
   (add-assistant-message! records provenance — pi messages carry the same);
   entries without a stamp (legacy files) fall back to the latest preceding
   :model-change entry, then \"unknown\" — old sessions never recorded their
   initial model. Tool-result and compaction/branch-summary usage groups
   under \"Tools/summaries\". Returns [{:key str :cost double :tokens long}
   ...] sorted by cost desc, filtered to buckets with cost or tokens."
  [session]
  (let [;; Walk entries in order: an assistant entry uses its own stamp when
        ;; present, else the latest preceding :model-change, else "unknown".
        {buckets :buckets}
        (reduce (fn [{:keys [model] :as acc} e]
                  (case (:role e)
                    :model-change
                    (assoc acc :model (provider-model-key e))

                    :assistant
                    (if-let [u (usage/entry-usage (:usage e))]
                      (let [key (cond
                                  (and (:provider e) (:model e)) (provider-model-key e)
                                  model model
                                  :else "unknown")]
                        (update-in acc [:buckets key] #(merge-with + % u)))
                      acc)

                    (:tool :bash :compaction :branch-summary)
                    (if-let [u (usage/entry-usage (:usage e))]
                      (update-in acc [:buckets "Tools/summaries"] #(merge-with + % u))
                      acc)

                    acc))
                {:model nil :buckets {}}
                @(:entries session))]
    (->> buckets
         (map (fn [[key {:keys [input output cache-read cache-write cost]}]]
                {:key key
                 :cost cost
                 :tokens (+ input output cache-read cache-write)}))
         (filter #(or (pos? (:cost %)) (pos? (:tokens %))))
         (sort-by :cost >)
         vec)))

(defn get-last-activity-ms
  "Last message activity time as epoch ms (pi: modified — the latest message
   timestamp), falling back to the file mtime, then the header :created-at
   (a lazy session has no file until the first assistant message — G4).
   An unparseable timestamp (corrupt/legacy file) falls back rather than
   throwing."
  [session]
  (let [last-ts (some-> (filter #(contains? % :timestamp) @(:entries session))
                        last
                        :timestamp)]
    (or (when last-ts
          (try (-> (java.time.Instant/parse last-ts) (.toEpochMilli))
               (catch Exception _ nil)))
        (when (fs/exists? (:file session))
          (.toMillis (fs/last-modified-time (:file session))))
        (when-let [created (:created-at (:header session))]
          (try (-> (java.time.Instant/parse created) (.toEpochMilli))
               (catch Exception _ nil)))
        0)))