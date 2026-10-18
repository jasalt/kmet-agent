(ns kmet.agent.prompts
  "Prompt templates for kmet, aligned with pi (pi: core/prompt-templates.js,
   docs/prompt-templates.md). A template is a .md file in the prompts dir
   (non-recursive) with optional YAML frontmatter: description (falls back to
   the first non-empty body line, truncated to 60 chars) and argument-hint.
   The filename without .md is the command name: /name args expands to the
   template body with $1, $@, ${1:-default}, ${@:N} placeholders substituted.

   Templates live in the app root's store, under the `[:app :prompts]` owner
   (`kmet.agent.runtime` mounts the `:prompts` slot for it, so their keys
   report under it in `/units`), addressed by `[:kmet/prompt <name>]`: visible
   to every engine, outliving one, and disposed with their owner rather than
   copied into an engine and reset by hand (cells.md §5.1). A name holds one
   template, so re-registering replaces the earlier one — pi's loader assigns
   into a name-keyed map the same way. The extension ABI registers here
   today; P5 gives each extension its own owner."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [kmet.agent.runtime :as app-runtime]
            [kmet.cells.scope :as scope]
            [kmet.libs.yaml :as yaml]))

;; ─── Template state ────────────────────────────────────────────────────────

(def prompts-owner
  "The app's prompt-template registry owner: the `:prompts` slot
   kmet.agent.runtime mounts at the root for it (cells.md §5.1)."
  [:app :prompts])

(defn- prompt-key
  "The store key addressing a template by NAME."
  [name]
  [:kmet/prompt name])

(defn- prompt-key?
  "True for a key the store holds a prompt template under — other registries'
   keys share the store, so shape is checked before the head."
  [k]
  (and (vector? k) (= :kmet/prompt (first k))))

(defn- registered-prompt-templates
  "The tree's prompt templates, in registration order (a re-registration of a
   name moves the template to the end)."
  []
  (into []
        (keep (fn [[k _ v]] (when (prompt-key? k) v)))
        (scope/ordered-registrations (app-runtime/node))))

;; ─── Argument parsing (pi: parseCommandArgs) ──────────────────────────────

(def ^:private js-space-separators
  "JS \\s space separators beyond what Character/isWhitespace covers:
   the NBSP-family (Zs) minus the no-break spaces isWhitespace already
   excludes, plus the Zl/Zp line/paragraph separators and the FEFF BOM.
   Character/isSpaceChar (Zs+Zl+Zp) is unshimmed on Jolt, so the set is
   explicit (pi: /\\s/ in parseCommandArgs)."
  #{\u00a0 \u1680 \u2000 \u2001 \u2002 \u2003 \u2004 \u2005 \u2006 \u2007
    \u2008 \u2009 \u200a \u2028 \u2029 \u202f \u205f \u3000 \ufeff})

(defn- js-whitespace?
  "True for characters JS /\\s/ treats as whitespace: Java's
   Character/isWhitespace misses the NBSP-family (incl. U+FEFF BOM), so union
   with the explicit separator set (pi: /\\s/ in parseCommandArgs)."
  [c]
  (or (Character/isWhitespace c)
      (contains? js-space-separators c)))

(defn parse-command-args
  "Parse command arguments respecting quoted strings (bash-style, pi:
   parseCommandArgs). Returns a vector of argument strings."
  [args-string]
  (let [args (volatile! [])
        cur (volatile! [])
        q (volatile! nil)]
    (doseq [c args-string]
      (if @q
        (if (= c @q) (vreset! q nil) (vswap! cur conj c))
        (cond
          (or (= c \") (= c \')) (vreset! q c)
          (js-whitespace? c)
          (do (when (seq @cur) (vswap! args conj (str/join @cur)))
              (vreset! cur []))
          :else (vswap! cur conj c))))
    (when (seq @cur) (vswap! args conj (str/join @cur)))
    @args))

;; ─── Argument substitution (pi: substituteArgs) ───────────────────────────

(defn- parse-long-safe
  "parse-long returning nil on non-numeric/overflow input (pi's parseInt
   clamps instead of throwing)."
  [s]
  (try (parse-long s) (catch Exception _ nil)))

(defn substitute-args
  "Substitute argument placeholders in template content (pi: substituteArgs):
   $1, $2, ... positional; $@ and $ARGUMENTS for all args; ${N:-default} with
   a default when missing/empty; ${@:-default} / ${ARGUMENTS:-default}; and
   bash-style slicing ${@:N} / ${@:N:L} (1-indexed). Single pass — values are
   not recursively substituted."
  [content args]
  (let [all-args (str/join " " args)
        n (count args)]
    (str/replace content
                 #"\$\{(\d+|ARGUMENTS|@):-([^}]*)\}|\$\{@:(\d+)(?::(\d+))?\}|\$(ARGUMENTS|@|\d+)"
                 (fn [[_ default-target default-value slice-start slice-length simple]]
                   (cond
                     default-target
                     (if (or (= default-target "@") (= default-target "ARGUMENTS"))
                       (if (seq all-args) all-args default-value)
                       (let [i (parse-long-safe default-target)
                             value (when (and i (>= i 1) (<= i n)) (nth args (dec i)))]
                         (if (seq value) value default-value)))
                     slice-start
                     (let [start (if-let [i (parse-long-safe slice-start)]
                                   (min n (max 0 (dec i)))
                                   n)
                           end (if-let [l (parse-long-safe slice-length)]
                                 (min n (+ start l))
                                 n)]
                       (str/join " " (subvec args start end)))
                     :else
                     (if (or (= simple "@") (= simple "ARGUMENTS"))
                       all-args
                       (if-let [i (parse-long-safe simple)]
                         (nth args (dec i) "")
                         "")))))))

;; ─── Loading (pi: loadTemplateFromFile / loadTemplatesFromDir) ────────────

(defn- parse-template-content
  "Parse RAW .md content into a prompt template (pi: loadTemplateFromFile
   body). LOCATION is the display locator (a file path, or an
   `ext-name:relative/path` locator for extension templates); EXTENSION is
   the owning extension name, or nil. Description = a string frontmatter
   description, else the first non-empty body line truncated to 60 chars
   with \"...\" (pi: only string frontmatter values count). Returns
   {:template map-or-nil :diagnostics [warning-maps]}."
  [raw name location extension]
  (try
    (let [{:keys [frontmatter body]} (yaml/parse-frontmatter raw)
          fm-desc (let [d (get frontmatter "description")]
                    (when (string? d) (str/trim d)))
          fm-hint (let [h (get frontmatter "argument-hint")]
                    (when (string? h) h))
          first-line (first (filter #(seq (str/trim %)) (str/split-lines body)))
          description (cond
                        (seq fm-desc) fm-desc
                        first-line (if (> (count first-line) 60)
                                     (str (subs first-line 0 60) "...")
                                     first-line)
                        :else "")]
      {:template
       (cond-> {:name name
                :description description
                :content body
                :location (str location)
                :extension extension
                :file-path (when (nil? extension) (str location))}
         (seq fm-hint) (assoc :argument-hint fm-hint))
       :diagnostics []})
    (catch Exception e
      {:template nil
       :diagnostics [{:type "warning"
                      :message (or (ex-message e) "failed to parse prompt template file")
                      :path (str location)}]})))

(defn- make-template-from-content
  "Build a prompt template map from RAW .md content (extension templates).
   Returns the template map, or nil on parse failure — registration skips a
   bad template; the file loading path reports diagnostics instead."
  [raw name location extension]
  (:template (parse-template-content raw name location extension)))

(defn- load-template-from-file
  "Load a prompt template from a .md file (name = filename without .md).
   Returns {:template map-or-nil :diagnostics [warning-maps]} — an
   unreadable file or malformed frontmatter is reported, not silently
   skipped (pi: loadTemplateFromFile)."
  [file-path]
  (try
    (parse-template-content (slurp file-path)
                            (str/replace (fs/file-name file-path) #"\.md$" "")
                            (str file-path)
                            nil)
    (catch Exception e
      {:template nil
       :diagnostics [{:type "warning"
                      :message (or (ex-message e) "failed to read prompt template file")
                      :path (str file-path)}]})))

(defn register-prompt-template!
  "Register a prompt template from an extension's bundled .md content string
   (the extension reads its own resource via io/resource and hands the
   content over, so jarred templates need no filesystem path). A name holds
   one template: re-registering under the same name replaces the earlier
   one.
   OPTS: :name (command name), :content (raw .md), :location (display
   locator, e.g. `my-ext:prompts/foo.md`), :extension (owner name).
   Returns a deregister fn removing exactly this template — a no-op when the
   content failed to parse."
  [{:keys [name content location extension]}]
  (if-let [t (make-template-from-content content name
                                         (or location (str (or extension "prompt")))
                                         extension)]
    (scope/register! (app-runtime/node) prompts-owner (prompt-key (:name t)) t)
    (fn [] nil)))

(defn prompt-template-files-in-dir
  "Prompt template .md files directly inside DIR (non-recursive, pi:
   loadTemplatesFromDir discovery). Returns the ordered file paths."
  [dir]
  (let [d (io/file dir)]
    (if-not (fs/directory? d)
      []
      (->> (fs/list-dir d)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".md")))
           (map str)
           vec))))

(defn load-prompt-template-files!
  "Load prompt templates from explicit .md file paths (the package-resource
   unit, pi: package prompts load). Adds them to the registry, reports load
   failures as warnings on stderr (pi: prompt resource diagnostics), and
   returns the loaded templates."
  [file-paths]
  (let [loaded (volatile! [])
        warnings (volatile! [])]
    (doseq [f file-paths]
      (when (str/ends-with? (fs/file-name (str f)) ".md")
        (let [result (load-template-from-file (str f))]
          (when-let [t (:template result)]
            (vswap! loaded conj t))
          (vswap! warnings into (:diagnostics result)))))
    (doseq [{:keys [message path]} @warnings]
      (binding [*out* *err*]
        (println (str "Warning: prompt template at " path ": " message))))
    (let [ts @loaded
          node (app-runtime/node)]
      (doseq [t ts]
        (scope/register! node prompts-owner (prompt-key (:name t)) t))
      ts)))

(defn load-prompt-templates-from-dir
  "Load .md prompt templates from a directory (non-recursive, pi:
   loadTemplatesFromDir). Adds them to the registry; returns the loaded
   templates."
  [dir]
  (load-prompt-template-files! (prompt-template-files-in-dir dir)))

(defn get-prompt-templates
  []
  (registered-prompt-templates))

(defn clear-prompt-templates!
  "Remove every loaded prompt template (pi: resourceLoader.reload
   re-discovers from scratch). Used by /reload."
  []
  (let [node (app-runtime/node)]
    (doseq [[k owner v] (scope/ordered-registrations node)
            :when (prompt-key? k)]
      (scope/deregister! node owner k v)))
  nil)

(defn get-prompt-template
  [name]
  (first (filter #(= name (:name %)) (registered-prompt-templates))))

;; ─── Expansion (pi: expandPromptTemplate) ─────────────────────────────────

(defn expand-prompt-template
  "Expand /name args into the template body when name matches a template.
   Returns the expanded content, or the original text when not a template
   (pi: expandPromptTemplate)."
  [text template-list]
  (if-not (str/starts-with? text "/")
    text
    (if-let [[_ name args-string] (re-matches #"^/([^\s]+)(?:\s+([\s\S]*))?$" text)]
      (if-let [t (first (filter #(= name (:name %)) template-list))]
        (substitute-args (:content t) (parse-command-args (or args-string "")))
        text)
      text)))

;; ─── Autocomplete (pi: interactive-mode templateCommands) ─────────────────

(defn as-command-maps
  "Templates in slash-command shape for the editor autocomplete provider
   (pi: interactive-mode converts templates to SlashCommand format)."
  [template-list]
  (mapv (fn [t]
          (cond-> {:name (:name t)
                   :description (:description t)}
            (:argument-hint t) (assoc :argument-hint (:argument-hint t))))
        template-list))
