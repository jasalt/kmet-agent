(ns kmet.app.modes.server-unix
  "The unix-domain transport for `kmet.app.modes.server`.

   This lives in its own namespace because **loading it is the capability
   check**. `java.net.UnixDomainSocketAddress` and the NIO channel API are not
   on every host kmet runs on — a Jolt build has neither — so the server
   resolves this namespace only when it is about to open a unix socket and
   falls back to TCP loopback when the load fails. A static require would take
   the whole mode row down on the hosts that cannot serve one.

   The contract is the server's transport shape, so the rest of the row never
   learns which kind of socket it is talking to: `:accept` yields the client's
   `{:in :out :close!}` — the same map a TCP client socket yields — and
   `:close!` ends the listener and unlinks the path."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel]))

(defn available?
  "This host can open a unix-domain socket: the NIO classes resolve *now*, not
   only at the first member call. The probe is a `Class/forName` and not the
   namespace load, because a host may accept the import and fail on first use —
   Jolt loads this file with the imports unresolved, so loading it proves
   nothing."
  []
  (try
    (Class/forName "java.net.UnixDomainSocketAddress")
    (Class/forName "java.nio.channels.ServerSocketChannel")
    true
    (catch Throwable _ false)))

(defn open
  "A transport listening on the unix-domain socket at PATH.

   The path is absolutized, its parent directories are created, and a file
   already at it is removed before binding: a socket left behind by a crashed
   server must not make the next one refuse to start. A *regular* file at the
   path is refused by name instead — `--socket` must never delete somebody's
   file because they named it. `:socket` is the path it bound (the `:listening`
   record announces it), and `:port` is absent — there is no port to announce."
  [path]
  (let [path (str (fs/absolutize path))
        _ (when-let [parent (fs/parent path)] (fs/create-dirs parent))
        _ (when (fs/regular-file? path)
            (throw (ex-info (str "refusing to replace a regular file with a unix socket: " path)
                            {:type :kmet.app/socket-path-occupied :path path})))
        _ (fs/delete-if-exists path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind server (UnixDomainSocketAddress/of path))
    {:kind :unix
     :socket path
     :accept (fn []
               (when-let [peer (try (.accept server) (catch Throwable _ nil))]
                 {:in (io/reader (Channels/newInputStream peer))
                  :out (io/writer (Channels/newOutputStream peer))
                  :close! (fn [] (try (.close peer) (catch Throwable _)))}))
     :close! (fn []
               (try (.close server) (catch Throwable _))
               (fs/delete-if-exists path))}))
