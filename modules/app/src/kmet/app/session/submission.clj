(ns kmet.app.session.submission
  "The front end's submission chain, below both drivers.

   pi: `session.prompt`'s input event — a submitted text runs the extension
   input hooks, expands (the front end's skill/template chain), and then either
   steers a running turn or starts one; while a compaction runs it queues
   instead. The chain is one implementation because kmet1's engine and a handle
   answer the same way; what differs is *how* a delivery happens, so that is
   the parameter (`:deliver!` for a submit, `:start!` for a queue flush).
 
   Nothing here requires `kmet.app.session.driver` or the loop driver: the
   driver and the handle both call it, and it must stay below both."
  (:require [clojure.string :as str]
            [kmet.agent.commands :as commands]
            [kmet.agent.debug :as debug]
            [kmet.agent.extensions.core :as extensions]
            [kmet.agent.loop.state :as loop-state]
            [kmet.app.session.compaction :as manual]
            [kmet.app.session.runtime :as runtime]))

(defn- split-command
  "Split a slash-command line into [name args]. Non-command text returns
   [nil nil]."
  [text]
  (when (str/starts-with? text "/")
    (let [sp (str/index-of text " ")]
      (if sp
        [(subs text 1 sp) (subs text (inc sp))]
        [(subs text 1) ""]))))

(defn extension-command?
  "True when TEXT is a registered extension command (pi: isExtensionCommand)."
  [text]
  (some? (some-> (split-command text) first commands/find-command :extension-handler)))

(defn execute-extension-command!
  "Run a registered extension command with the extension context (pi:
   prompt() → _tryExecuteExtensionCommand → handler(args, ctx))."
  [text]
  (let [[name args] (split-command text)
        c (commands/find-command name)]
    ((:extension-handler c) (extensions/build-extension-context) (or args ""))))

(defn expand-text
  "TEXT through ENGINE's own expansion chain (`:expand-input`) — kmet1's skill
   commands and prompt templates — or as written when the front end named
   none. A queued message is raw (pre-hook); the flush expands it here."
  [engine text]
  (if-some [expand! (:expand-input engine)]
    (expand! text)
    text))

(defn expand-user-message-text
  "pi: prompt()'s expansion chain (sendUserMessage with
   :expand-prompt-templates?): extension commands execute immediately and
   consume the message; then skill commands and prompt templates expand.
   Builtin commands are NOT dispatched (pi: _tryExecuteExtensionCommand only).
   Returns the expanded text, or nil when an extension command consumed the
   message."
  [engine text]
  (let [trimmed (str/trim text)]
    (if (and (str/starts-with? trimmed "/")
             (not (str/includes? trimmed "\n")))
      (let [space (str/index-of trimmed " ")
            cmd (if (nil? space) (subs trimmed 1) (subs trimmed 1 space))
            args (if (nil? space) "" (str/trim (subs trimmed (inc space))))]
        (if-let [c (commands/find-command cmd)]
          (if-let [eh (:extension-handler c)]
            (do (eh (extensions/build-extension-context) args)
                nil ;; consumed — nothing to send
                )
            text) ;; builtin command — not dispatched here (pi parity)
          (expand-text engine text)))
      text)))

(defn- apply-input-hooks
  "Run extension input hooks on TEXT; returns the (possibly transformed)
   text, or nil when a hook consumed the input (pi: session.prompt input
   event). :streaming-behavior tells hooks whether the input will be
   steered."
  [engine text mode]
  (let [input (extensions/apply-input-hooks text mode
                                            {:streaming-behavior (when (runtime/turn-running? engine) :steer)})]
    (if (= :handled (:action input))
      (do (debug/log "input handled by extension: " text) nil)
      (if (contains? input :text) (:text input) text))))

(defn submit!
  "Submit TEXT the way a front end does (pi: session.prompt's input chain).
   While a compaction runs the raw text queues as a steer message; otherwise
   the extension input hooks run (a :handled input is consumed), the text is
   expanded when :expand? is set, and the delivery fn decides between steering
   and starting.

   OPTS:
     :deliver!  (fn [text target run-opts] → :started | :steered) — required;
                the one start-or-queue rule of the caller (the handle's
                `deliver!`, or the driver's prompt-or-queue). RUN-OPTS is
                `:run-opts` as given: the caller's own additions to the start
                (rpc's images), which a queue cannot carry.
     :run-opts  the start's additions, forwarded to :deliver!.
     :on-deliver! (fn [text target] → entry) — optional; called once, just
                before a delivery happens, so a caller that records the
                queue fact (the wire's durable inbox) records it only where
                a message is actually delivered, and never for input a hook
                consumed. The returned entry rides :run-opts to :deliver!.
     :expand?   run the expansion chain (pi: the unknown-slash-command path).
     :mode      the front end kind the input hooks see; defaults :interactive.

   Returns {:action :compaction-queued | :handled | :started | :steered,
   :text <hook-transformed text>} — the front end records :text in its editor
   history."
  ([engine text opts]
   (let [{:keys [deliver! expand? mode run-opts on-deliver!]
          :or {mode :interactive}} opts]
     (when-not (fn? deliver!)
       (throw (ex-info "a submission needs a delivery fn: name one (:deliver!)"
                       {:type :kmet.app/no-delivery})))
     (if (runtime/compacting? engine)
       (do (manual/queue-compaction-message! engine text :steer)
           {:action :compaction-queued :text text})
       (if-let [hooked (apply-input-hooks engine text mode)]
         (let [out (if expand? (expand-text engine hooked) hooked)
               run-opts (if on-deliver!
                          (assoc run-opts :entry (on-deliver! out :next-step))
                          run-opts)]
           {:action (deliver! out :next-step run-opts) :text hooked})
         {:action :handled})))))

(defn- deliver-queued-message!
  "Deliver one compaction-queued M: extension commands execute immediately;
   otherwise the message queues into the run per its mode (steer/follow-up,
   with skill/template expansion). With :allow-prompt? and no run active, the
   idle path runs the input hooks and starts a prompt instead (pi:
   flushCompactionQueue → session.prompt); the overflow-retry flush never
   prompts — every message queues via steer/followUp."
  [engine m {:keys [allow-prompt? start!] :as opts}]
  (if (extension-command? (:text m))
    (execute-extension-command! (:text m))
    (if (and allow-prompt? (not (runtime/turn-running? engine)))
      (when-let [text (apply-input-hooks engine (:text m) (:mode opts :interactive))]
        (start! (expand-text engine text)))
      (let [text (expand-text engine (:text m))]
        (if (= :follow-up (:mode m))
          (loop-state/follow-up! (runtime/agent-state engine) text)
          (loop-state/steer! (runtime/agent-state engine) text))))))

(defn flush-compaction-queue!
  "Deliver messages queued during compaction (pi: flushCompactionQueue — runs
   on every compaction_end). With WILL-RETRY (overflow compaction: the
   interrupted turn continues), every message queues into the running turn via
   steer/follow-up per its mode. Otherwise the first non-extension message
   starts a prompt when idle, extension commands execute immediately, and the
   rest queue per mode. OPTS: :start! (fn [text]) for a prompt this starts,
   :mode for the input hooks. A failure restoring the queue re-queues the
   messages so they are not lost; returns nil, or {:error e} for the front end
   to report."
  [engine will-retry opts]
  (let [msgs (runtime/compaction-queued engine)]
    (when (seq msgs)
      (manual/clear-compaction-queue! engine)
      (let [restore! (fn []
                       ;; pi: restoreQueue — the session queue is cleared too,
                       ;; so messages already delivered by the failed flush are
                       ;; not delivered again when the queue is re-flushed
                       (loop-state/clear-queues! (runtime/agent-state engine))
                       (reset! (:compaction-queued engine) msgs))]
        (try
          (if will-retry
            ;; Overflow compaction: the turn retries — queue everything into it
            ;; (pi: flushCompactionQueue willRetry — steer/followUp only, never
            ;; a fresh prompt)
            (doseq [m msgs] (deliver-queued-message! engine m opts))
            ;; Normal completion: first non-extension message prompts, the
            ;; rest queue per mode
            (let [first-idx (first (keep-indexed
                                    (fn [i m] (when-not (extension-command? (:text m)) i))
                                    msgs))]
              (if first-idx
                (do
                  ;; extension commands before the first prompt execute now
                  (doseq [m (take first-idx msgs)]
                    (execute-extension-command! (:text m)))
                  ;; the first message: prompt when idle, else queue per mode
                  (deliver-queued-message! engine (nth msgs first-idx)
                                           (assoc opts :allow-prompt? true))
                  ;; remaining messages queue per mode — never a fresh prompt
                  ;; (pi: the rest go through steer/followUp)
                  (doseq [m (drop (inc first-idx) msgs)]
                    (deliver-queued-message! engine m opts)))
                ;; all extension commands — execute them all
                (doseq [m msgs] (execute-extension-command! (:text m))))))
          nil
          (catch Exception e
            (restore!)
            (debug/log "compaction queue flush failed: " e)
            {:error e :count (count msgs)}))))))
