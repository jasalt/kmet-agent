(ns kmet.app.front-end
  "The front-end surface a run installs for the extension host: the mode kind
   (`:mode`), whether a UI port exists (`has-ui`) and the port's capability map
   (see `kmet.agent.extensions.core`).

   A mode is not a front end for the process's life — it is one for its run. It
   installs the surface when it starts serving and puts back what was there when
   it ends: *what was there*, not the dispatcher's default, because a process can
   have a front end before it has this run (an embedder's port, another mode) and
   a run's teardown must not take it away."
  (:require [kmet.agent.extensions.core :as extensions]))

(defn install!
  "Bind SURFACE for the run and return the fn that restores the surface bound
   before it. Installing and restoring are one pair, so every mode writes the
   rule once: `(install! {:mode :rpc :has-ui true :ui port})`."
  [surface]
  (let [previous (extensions/front-end-surface)]
    (extensions/bind-extensions! surface)
    (fn [] (extensions/bind-extensions! previous))))
