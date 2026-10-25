(ns kmet.app.git-packages
  "Git package sources: URL parsing, managed checkouts, and their lifecycle
   (pi: utils/git.ts plus the git half of core/package-manager.ts).

   A git source is the `git:` shorthand (`git:github.com/user/repo`,
   `git:git@github.com:user/repo`), an explicit protocol URL without the
   prefix (`https://github.com/user/repo`, `ssh://…`, `git://…`), or any of
   those behind the prefix; `@ref` and `#ref` pin a checkout to a branch,
   tag, or commit, and `//subdir` names a directory inside the repository
   (a monorepo package: `git:host/repo//packages/foo`,
   `git:host/repo@v1//packages/foo`). Parsing is pi's parseGitUrl including
   its safety rules (no `..` segments, no backslashes, no absolute paths, a
   host plus a path of at least two segments), so a parsed source is safe
   to join under an install root.

   Checkouts are managed, not user files: install! clones one under
   ROOT/<host>/<path>, update! reconciles it in place (fetch + hard reset,
   `git clean -fdx` when the commit moved), and remove! deletes it and
   prunes the empty parents it leaves. The checkout is keyed by the
   repository (host/path) — every subdirectory source of one repository
   shares it, and only the package root (package-dir) adds the
   subdirectory. The caller supplies the root — kmet.app.packages owns
   the per-scope root (~/.kmet/agent/git or .kmet/git) — so this namespace
   stays filesystem-in/filesystem-out."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [kmet.libs.process :as process]))

;; ─── URL parsing (pi: utils/git.ts) ───────────────────────────────────────

(def ^:private protocol-pattern
  "The protocols pi's parseGitUrl accepts without the git: prefix."
  #"(?i)^(https?|ssh|git)://")

(defn- percent-decode
  "Decode %HH escapes; nil when a % escape is malformed (pi
   decodeForValidation — a malformed escape reads as unsafe)."
  [s]
  (if-not (str/includes? s "%")
    s
    (let [sb (StringBuilder.)
          n (count s)]
      (loop [i 0]
        (cond
          (>= i n) (str sb)

          (= \% (nth s i))
          (if (and (< (+ i 2) n)
                   (re-matches #"[0-9A-Fa-f]{2}" (subs s (inc i) (+ i 3))))
            (do (.append sb (char (Integer/parseInt (subs s (inc i) (+ i 3)) 16)))
                (recur (+ i 3)))
            nil)

          :else (do (.append sb (nth s i))
                    (recur (inc i))))))))

(defn- unsafe-part?
  "pi hasUnsafeGitInstallPart — a host/path carrying traversal or separator
   tricks is rejected. Checked percent-decoded too, so `%2e%2e` is a `..`."
  [value allow-slash?]
  (let [decoded (percent-decode value)]
    (if (nil? decoded)
      true
      (boolean
       (some (fn [candidate]
               (or (str/includes? candidate "\u0000")
                   (str/includes? candidate "\\")
                   (str/starts-with? candidate "/")
                   (and (not allow-slash?) (str/includes? candidate "/"))
                   (some #{".."} (str/split candidate #"/"))))
             [value decoded])))))

(defn- split-source-dir
  "Split BODY at the subdirectory separator `//` — the first one after a
   URL scheme (so `https://…` is not mistaken for one). Returns
   {:repo R :dir D}; :dir is nil when the source names the repository root."
  [body]
  (let [from (if-let [scheme (str/index-of body "://")] (+ scheme 3) 0)]
    (if-let [sep (str/index-of body "//" from)]
      {:repo (subs body 0 sep) :dir (subs body (+ sep 2))}
      {:repo body :dir nil})))

(defn- normalize-dir
  "The validated subdirectory of a git source: `./` and leading/trailing
   slashes dropped; nil for a blank, control-character, unsafe, or
   empty-segment path (the source then fails to parse)."
  [dir]
  (let [d (-> dir (str/replace #"^(?:\./|/)+" "") (str/replace #"/+$" ""))]
    (when (and (seq d)
               (not= d ".")
               (not (str/includes? d "//"))
               (not (re-find #"[\u0000-\u001f\u007f]" d))
               (not (unsafe-part? d true)))
      d)))

(defn- split-at-ref
  "Split PATH at its first @ into {:path p :ref r} — both parts must be
   non-blank for the ref to count (pi: splitRef's repoPath/ref check)."
  [path]
  (let [at (str/index-of path "@")]
    (if (or (nil? at)
            (str/blank? (subs path 0 at))
            (str/blank? (subs path (inc at))))
      {:path path :ref nil}
      {:path (subs path 0 at) :ref (subs path (inc at))})))

(defn- split-ref
  "pi splitRef — peel a trailing @REF off a git URL. Returns
   {:repo repo-without-ref :ref ref-or-nil}."
  [url]
  (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" url)]
    (let [{:keys [path ref]} (split-at-ref path)]
      {:repo (str "git@" host ":" path) :ref ref})
    (if-let [scheme-end (str/index-of url "://")]
      (let [slash (str/index-of url "/" (+ scheme-end 3))]
        (if (nil? slash)
          {:repo url :ref nil}
          (let [{:keys [path ref]} (split-at-ref (subs url (inc slash)))]
            {:repo (str (subs url 0 (inc slash)) path) :ref ref})))
      (let [slash (str/index-of url "/")]
        (if (nil? slash)
          {:repo url :ref nil}
          (let [{:keys [path ref]} (split-at-ref (subs url (inc slash)))]
            {:repo (str (subs url 0 slash) "/" path) :ref ref}))))))

(defn- url-host
  "The hostname of a protocol URL — the authority without userinfo and port
   (pi: new URL(...).hostname)."
  [url]
  (let [i (str/index-of url "://")
        after (subs url (+ i 3))
        slash (or (str/index-of after "/") (count after))
        authority (subs after 0 slash)
        no-user (if-let [at (str/last-index-of authority "@")]
                  (subs authority (inc at))
                  authority)]
    (if-let [port (str/last-index-of no-user ":")]
      (subs no-user 0 port)
      no-user)))

(defn- url-path
  "The path of a protocol URL without its leading slash, or nil (pi: new
   URL(...).pathname)."
  [url]
  (let [i (str/index-of url "://")
        after (subs url (+ i 3))
        slash (str/index-of after "/")]
    (when slash (subs after (inc slash)))))

(defn- safe-ref?
  "A ref kmet will pass to git: git's own refname rules for the unsafe
   characters, plus the two that matter as an argv element — a leading dash
   (an option-looking ref could smuggle e.g. --upload-pack into the fetch or
   checkout arguments) and control characters. A source carrying any other
   ref simply fails to parse."
  [ref]
  (and (not (str/starts-with? ref "-"))
       (not (str/starts-with? ref "/"))
       (not (str/ends-with? ref "."))
       (not (str/ends-with? ref "/"))
       (not (str/ends-with? ref ".lock"))
       (not (str/includes? ref ".."))
       (not (str/includes? ref "@{"))
       (not (re-find #"[\s~^:?*\[\\\u0000-\u001f\u007f]" ref))))

(defn- build-source
  "pi buildGitSource — validate and shape a parsed git source; nil when the
   host or path fails the install-safety checks."
  [{:keys [repo host path ref]}]
  (when (string? path)
    (let [ref (not-empty (str ref))
          normalized (-> path (str/replace #"\.git$" "") (str/replace #"^/+" ""))]
      (when (and (string? host)
                 (seq host)
                 (seq normalized)
                 (not (str/starts-with? path "/"))
                 (>= (count (str/split normalized #"/")) 2)
                 (not (unsafe-part? host false))
                 (not (unsafe-part? normalized true))
                 (or (nil? ref) (safe-ref? ref)))
        {:type :git
         :repo repo
         :host host
         :path normalized
         :ref ref
         :pinned? (boolean ref)}))))

(defn- parse-generic-url
  "pi parseGenericGitUrl — parse a repo URL, or a host/path shorthand that
   becomes an https URL when the host carries a dot. The ref must already
   be split off by split-ref."
  [url]
  (let [{:keys [repo ref]} (split-ref url)]
    (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" repo)]
      (build-source {:repo repo :host host :path path :ref ref})
      (if (re-find protocol-pattern repo)
        (build-source {:repo repo
                       :host (url-host repo)
                       :path (url-path repo)
                       :ref ref})
        (if-let [slash (str/index-of repo "/")]
          (let [host (subs repo 0 slash)
                path (subs repo (inc slash))]
            (cond
              (or (str/includes? host ".") (= host "localhost"))
              (build-source {:repo (str "https://" repo)
                             :host host
                             :path path
                             :ref ref})

              ;; hosted-git-info's bare shorthand: `user/repo` is GitHub
              ;; (pi's hostedGitInfo candidate)
              (= 2 (count (str/split repo #"/")))
              (build-source {:repo (str "https://github.com/" repo)
                             :host "github.com"
                             :path repo
                             :ref ref})

              :else nil))
          nil)))))

(defn parse-git-url
  "Parse a git package SOURCE (pi: parseGitUrl, extended with kmet's
   `//subdir`). Returns {:type :git :repo URL :host HOST :path PATH
   :ref REF-OR-NIL :dir DIR-OR-NIL :pinned? BOOL}, or nil when SOURCE is not
   a git repository.

   Accepted forms — the `git:` prefix admits the shorthand forms; explicit
   protocol URLs also parse without it:

     git:github.com/user/repo        git:github.com/user/repo@v1.2.0
     git:git@github.com:user/repo    git:https://github.com/user/repo
     https://github.com/user/repo    ssh://git@host/user/repo
     git://host/user/repo            https://github.com/user/repo#v1
     git:user/repo                   ;; bare shorthand = GitHub
     git:host/repo//packages/foo     git:host/repo@v1//packages/foo

   `//subdir` names a directory inside the repository (a monorepo package).
   It comes after the ref: `@ref`/`#ref` belong to the repository part and
   everything after `//` is the subdirectory (a `#` in it is part of the
   directory name)."
  [source]
  (let [trimmed (str/trim (str source))
        prefixed? (str/starts-with? trimmed "git:")
        body (if prefixed? (str/trim (subs trimmed 4)) trimmed)
        ;; `git://host/path` strips to `//host/path`; restore the scheme
        body (if (and prefixed? (str/starts-with? body "//"))
               (str "git:" body)
               body)
        {:keys [repo dir]} (split-source-dir body)
        has-dir? (some? dir)
        hash-at (str/index-of repo "#")
        fragment (when (and hash-at (pos? hash-at))
                   (not-empty (subs repo (inc hash-at))))
        repo (if (and hash-at (pos? hash-at)) (subs repo 0 hash-at) repo)]
    (when (and (seq repo)
               (or prefixed? (re-find protocol-pattern repo)))
      (when-let [parsed (parse-generic-url repo)]
        (let [dir (when has-dir? (normalize-dir dir))
              ref (or (:ref parsed) fragment)]
          (when (or (not has-dir?) dir)
            (assoc parsed :ref ref :dir dir :pinned? (boolean (seq ref)))))))))

(defn repo-identity
  "The repository identity of a parsed git SOURCE — host/path, ignoring the
   ref and subdirectory: what its managed checkout is keyed by."
  [source]
  (str (:host source) "/" (:path source)))

(defn git-identity
  "The identity of a parsed git SOURCE for dedupe (pi: getPackageIdentity,
   extended with kmet's subdirectory) — repository host/path plus `//dir`,
   so every URL form and ref of one package matches while two
   subdirectories of one repository stay distinct packages."
  [source]
  (str "git:" (repo-identity source)
       (when-let [dir (:dir source)] (str "//" dir))))

;; ─── Managed checkouts (pi: getGitInstallPath + install/update/remove) ────

(def ^:private network-timeout-ms
  "pi NETWORK_TIMEOUT_MS — probes and fetches are bounded; a clone is not
   (a large repository legitimately takes longer)."
  10000)

(defn- contained?
  "True when P lexically resolves under ROOT."
  [root p]
  (try
    (let [rel (fs/relativize root p)]
      (and (not (fs/absolute? rel))
           (not (str/starts-with? (str rel) ".."))))
    (catch Exception _ false)))

(defn install-path
  "The managed checkout path of SOURCE under ROOT (pi: getGitInstallPath):
   ROOT/host/path, guarded so no source can escape ROOT. The checkout is
   shared by every subdirectory source of the same repository."
  [root source]
  (let [root (str (fs/normalize (str root)))
        target (str (fs/normalize (fs/path root (:host source) (:path source))))]
    (when-not (contained? root target)
      (throw (ex-info (str "Refusing to use path outside the git install root: " target)
                      {:type :git-bad-path :path target :root root})))
    target))

(defn package-dir
  "The package root of SOURCE under ROOT: its managed checkout when the
   source names the repository root, or checkout/dir for a `//subdir`
   source (a directory, or a single file — kmet.app.packages loads a file
   root as one extension). Subdirectories of one repository share the
   checkout; the dir is re-checked for containment even though parse-git-url
   already validated it."
  [source root]
  (let [checkout (install-path root source)
        target (if-let [dir (:dir source)]
                 (str (fs/path checkout dir))
                 checkout)]
    (when-not (contained? checkout target)
      (throw (ex-info (str "Refusing to use path outside the git checkout: " target)
                      {:type :git-bad-path :path target :root checkout})))
    target))

(defn installed?
  "True when SOURCE is installed under ROOT — the checkout exists (and for
   a `//subdir` source the directory exists in it)."
  [source root]
  (fs/exists? (package-dir source root)))

(defn package-dir-error
  "The user-facing message for a git source whose `//dir` does not resolve
   under its checkout, with the checkout's immediate non-hidden
   directories as candidates. The checkout itself is left in place — a
   sibling package of the same repository may share it."
  [source root]
  (let [checkout (install-path root source)
        dirs (when (fs/directory? checkout)
               (->> (fs/list-dir checkout)
                    (filter fs/directory?)
                    (map fs/file-name)
                    (remove #(str/starts-with? (str %) "."))
                    (map str)
                    sort
                    vec))]
    (str "No directory \"" (:dir source) "\" in " (repo-identity source)
         (if (seq dirs)
           (str " (directories: " (str/join ", " dirs) ")")
           " (the repository has no subdirectories)"))))

(defn git-available?
  "True when a git binary is on PATH."
  []
  (some? (fs/which "git")))

(defn- await-git
  "Deref PROC, bounded by TIMEOUT-MS when non-nil. A timed-out command's
   process tree is killed and :git-timeout is thrown."
  [proc timeout-ms args]
  (if (nil? timeout-ms)
    @proc
    (let [result (deref (future @proc) timeout-ms ::timeout)]
      (if (= ::timeout result)
        (do (some-> (process/process-pid proc) process/kill-process-tree!)
            (throw (ex-info (str "git " (str/join " " (map str args))
                                 " timed out after " timeout-ms "ms")
                            {:type :git-timeout :args (vec args)})))
        result))))

(defn- run-git
  "Run git ARGS (OPTS: :dir, :timeout-ms) with captured output. Returns
   {:exit n :out s :err s}. Throws :git-not-found when git is missing and
   :git-timeout when TIMEOUT-MS elapses."
  [opts & args]
  (when-not (git-available?)
    (throw (ex-info "git is not installed (or not on PATH)" {:type :git-not-found})))
  (let [proc (apply p/process
                    (cond-> {:out :string
                             :err :string
                             :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                      (:dir opts) (assoc :dir (str (:dir opts))))
                    "git" (mapv str args))]
    (await-git proc (:timeout-ms opts) args)))

(defn- failed-message
  [args exit err out]
  (let [detail (let [e (str/trim (str err))
                     o (str/trim (str out))]
                 (cond (seq e) e (seq o) o :else nil))]
    (str "git " (str/join " " (map str args)) " failed with code " exit
         (when detail (str ": " detail)))))

(defn- git!
  "Run git ARGS and return trimmed stdout; throws :git-command-failed on a
   non-zero exit (pi: runCommandCapture)."
  [opts & args]
  (let [{:keys [exit out err]} (apply run-git opts args)]
    (when-not (zero? (or exit -1))
      (throw (ex-info (failed-message args exit err out)
                      {:type :git-command-failed
                       :args (vec args)
                       :exit exit
                       :stderr (str/trim (str err))})))
    (str/trim (str out))))

(defn- git-ok!
  "Run git ARGS, discarding output; throws on a non-zero exit (pi:
   runCommand)."
  [opts & args]
  (apply git! opts args)
  nil)

(defn- git-optional
  "Trimmed stdout when the command succeeds, nil on any failure (pi:
   runCommandCapture(...).catch(() => \"\"))."
  [opts & args]
  (try
    (apply git! opts args)
    (catch Exception _ nil)))

(defn- ensure-install-root!
  "Create ROOT and seed the managed-checkout .gitignore (pi ensureGitIgnore),
   so a checkout root inside a project never appears as user files."
  [root]
  (fs/create-dirs root)
  (let [ignore (fs/path root ".gitignore")]
    (when-not (fs/exists? ignore)
      (spit (str ignore) "*\n!.gitignore\n")))
  root)

(defn- prune-empty-parents!
  "Remove the empty host/path parents TARGET leaves behind, up to ROOT (pi:
   pruneEmptyGitParents)."
  [target root]
  (let [root (str (fs/normalize (str root)))]
    (loop [dir (str (fs/normalize (str (fs/parent target))))]
      (when (and (not= dir root)
                 (< (count root) (count dir))
                 (str/starts-with? dir root)
                 (fs/directory? dir)
                 (empty? (fs/list-dir dir)))
        (fs/delete dir)
        (recur (str (fs/normalize (str (fs/parent dir)))))))))

(defn- upstream-fetch
  "pi getLocalGitUpdateTarget's upstream branch —
   {:fetch-args [...] :ref \"@{upstream}\"} when the checkout tracks an
   origin/ branch, else nil."
  [target]
  (when-let [upstream (git-optional {:dir target :timeout-ms network-timeout-ms}
                                    "rev-parse" "--abbrev-ref" "@{upstream}")]
    (when (str/starts-with? upstream "origin/")
      (let [branch (subs upstream (count "origin/"))]
        (when (seq branch)
          {:fetch-args ["fetch" "--prune" "--no-tags" "origin"
                        (str "+refs/heads/" branch ":refs/remotes/origin/" branch)]
           :ref "@{upstream}"})))))

(defn- default-branch-fetch
  "pi getLocalGitUpdateTarget's fallback — refresh origin/HEAD through the
   remote's default branch when it can be resolved."
  [target]
  (git-optional {:dir target} "remote" "set-head" "origin" "-a")
  (let [symbolic (git-optional {:dir target :timeout-ms network-timeout-ms}
                               "symbolic-ref" "refs/remotes/origin/HEAD")
        branch (when symbolic
                 (str/replace symbolic #"^refs/remotes/origin/" ""))]
    (if (seq branch)
      {:fetch-args ["fetch" "--prune" "--no-tags" "origin"
                    (str "+refs/heads/" branch ":refs/remotes/origin/" branch)]
       :ref "origin/HEAD"}
      {:fetch-args ["fetch" "--prune" "--no-tags" "origin"
                    "+HEAD:refs/remotes/origin/HEAD"]
       :ref "origin/HEAD"})))

(defn- remote-update-target
  "How to refresh an unpinned checkout (pi: getLocalGitUpdateTarget)."
  [target]
  (or (upstream-fetch target)
      (default-branch-fetch target)))

(defn- reconcile!
  "pi ensureGitRef — fetch FETCH-ARGS, then hard-reset the checkout to REF
   when the commit moved; a changed checkout is cleaned of untracked files
   (managed checkouts stay pristine)."
  [target fetch-args ref]
  (apply git-ok! {:dir target :timeout-ms network-timeout-ms} fetch-args)
  (let [commit (str ref "^{commit}")
        local-head (git! {:dir target :timeout-ms network-timeout-ms} "rev-parse" "HEAD")
        target-head (git! {:dir target :timeout-ms network-timeout-ms} "rev-parse" commit)]
    (when-not (= local-head target-head)
      (git-ok! {:dir target} "reset" "--hard" commit)
      (git-ok! {:dir target} "clean" "-fdx"))))

(declare update!)

(defn install!
  "Clone SOURCE into its managed checkout under ROOT (pi: installGit); an
   existing checkout is reconciled in place. Returns the checkout path. A
   failed clone cleans up after itself. The checkout is repository-scoped:
   a `//dir` source is not checked here — the caller validates the package
   directory (package-dir) after install, since a checkout can be shared by
   sibling subdirectory packages."
  [source root]
  (let [target (install-path root source)]
    (if (fs/exists? target)
      (update! source root)
      (do
        (ensure-install-root! root)
        (fs/create-dirs (fs/parent target))
        (try
          (git-ok! nil "clone" (:repo source) target)
          (when-let [ref (:ref source)]
            (git-ok! {:dir target :timeout-ms network-timeout-ms} "checkout" ref))
          target
          (catch Exception e
            (when (fs/exists? target) (fs/delete-tree target))
            (prune-empty-parents! target root)
            (throw e)))))))

(defn update!
  "Reconcile the managed checkout of SOURCE under ROOT (pi: updateGit):
   install it when missing; a pinned source is fetched and reset to its
   configured ref, an unpinned one to its origin upstream (or default)
   branch. Returns the checkout path."
  [source root]
  (let [target (install-path root source)]
    (if-not (fs/exists? target)
      (install! source root)
      (do
        (if-let [ref (:ref source)]
          (reconcile! target ["fetch" "origin" ref] "FETCH_HEAD")
          (let [{:keys [fetch-args ref]} (remote-update-target target)]
            (reconcile! target fetch-args ref)))
        target))))

(defn remove!
  "Delete SOURCE's managed checkout under ROOT and prune the empty parents
   it leaves (pi: removeGit). No-op when not installed; returns the path it
   removed."
  [source root]
  (let [target (install-path root source)]
    (when (fs/exists? target)
      (fs/delete-tree target))
    (prune-empty-parents! target root)
    target))
