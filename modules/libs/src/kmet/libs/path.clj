(ns kmet.libs.path
  "Shared helpers for filesystem exploration used by opt-in search tools."
  (:require [babashka.fs :as fs]))

(def ^:private max-traverse-files
  "Traversal cap shared with the retired builtin (10k files)."
  10000)

(defn safe-file-seq
  "Like file-seq with symlink-cycle protection, a max-files cap and `.git`
   skipped — a repo's object store is never source-search material."
  [dir-path]
  (let [visited (atom #{})]
    (take max-traverse-files
          (filter fs/regular-file?
                  (tree-seq
                   (fn [f]
                     (and (fs/directory? f)
                          (not= ".git" (fs/file-name f))
                          (let [cp (fs/canonicalize f)]
                            (when-not (contains? @visited cp)
                              (swap! visited conj cp)
                              true))))
                   (fn [d] (fs/list-dir d))
                   (fs/file dir-path))))))

(defn resolve-tool-path-ctx
  "Resolve PATH against the runtime working directory carried in tool CTX.
   Absolute paths pass through; missing ctx or :cwd falls back to the
   process cwd."
  [ctx path]
  (let [p (str (or path "."))]
    (if (fs/absolute? p)
      p
      (str (fs/normalize
            (fs/path (or (:cwd ctx) (System/getProperty "user.dir")) p))))))
