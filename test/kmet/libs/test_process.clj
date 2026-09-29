(ns kmet.libs.test-process
  "kill-process-tree! must take the whole tree, not just the direct child:
   the descendant's delayed side effect proves it — it runs after the
   parent is already gone, so a direct-child-only kill leaves the marker
   behind. Windows exercises the taskkill /F /T branch, Unix the ps-walk
   fallback."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.test :as t]
            [kmet.libs.process :as process]))

(defn- temp-dir []
  (let [dir (str (fs/absolutize (str "target/test-process-" (System/nanoTime))))]
    (fs/create-dirs dir)
    dir))

(defn- write-windows-parent!
  "parent.cmd starts child.cmd, then blocks. The child reports in
   (STARTED), waits ~2s, then touches MARKER — so a descendant that
   survives the kill lands the marker after it."
  [dir]
  (let [parent (fs/path dir "parent.cmd")]
    (spit (str parent)
          (str "@echo off\r\n"
               "cd /d \"%~dp0\"\r\n"
               "start /b \"\" child.cmd \"%~1\" \"%~2\"\r\n"
               "ping -n 30 127.0.0.1 >NUL\r\n"))
    (spit (str (fs/path dir "child.cmd"))
          (str "@echo off\r\n"
               "echo x>\"%~2\"\r\n"
               "ping -n 3 127.0.0.1 >NUL\r\n"
               "echo x>\"%~1\"\r\n"))
    parent))

(defn- write-unix-parent!
  "parent.sh backgrounds the same child (a subshell), then blocks."
  [dir]
  (let [parent (fs/path dir "parent.sh")]
    (spit (str parent)
          (str "#!/bin/sh\n"
               "(touch \"$2\"; sleep 2; touch \"$1\") &\n"
               "sleep 30\n"))
    parent))

(t/deftest ^:slow kill-process-tree-kills-descendants
  (let [dir (temp-dir)
        marker (str (fs/path dir "marker"))
        started (str (fs/path dir "started"))
        spawned (atom nil)]
    (try
      (let [parent (if process/windows-os?
                     (write-windows-parent! dir)
                     (write-unix-parent! dir))
            p (proc/process (if process/windows-os?
                              ["cmd.exe" "/c" (str parent) marker started]
                              ["sh" (str parent) marker started])
                            {:out :discard :err :discard})]
        (reset! spawned p)
        (let [pid (process/process-pid p)]
          (t/is (some? pid) "the spawned shell exposes its pid")
          ;; wait for the descendant to exist, so the kill cannot race the
          ;; tree's creation and pass vacuously
          (let [deadline (+ (System/currentTimeMillis) 5000)]
            (while (and (not (fs/exists? started))
                        (< (System/currentTimeMillis) deadline))
              (Thread/sleep 50)))
          (t/is (fs/exists? started) "the descendant reported in before the kill")
          (process/kill-process-tree! pid)
          (t/is (not= ::timeout (deref p 5000 ::timeout))
                "the killed parent exited")
          ;; a survivor would touch the marker ~2s after reporting in
          (Thread/sleep 3000)
          (t/is (not (fs/exists? marker))
                "the killed descendant never touched the marker")))
      (finally
        (when-let [p @spawned]
          (try (proc/destroy-tree p) (catch Exception _ nil)))
        (try (fs/delete-tree dir) (catch Exception _ nil))))))
