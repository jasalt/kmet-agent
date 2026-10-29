(ns kmet.libs.log
  "The lower layers' logger seam.

   `kmet.libs.*` is rank 0 and must stay free of `kmet.app.*` requires (the
   rank guard and `kmet.libs.test-self-contained` hold it), yet a retry, a
   failed native probe or a background thread's throw is worth a diagnostic
   line. So the host installs its logger here once, and the libs call
   `log!`; with no sink installed this is silent, which is the default for
   a library used on its own.

   The sink must not throw and must not block: `log!` swallows a sink's
   error so a diagnostic can never fail the code it reports on. The app's
   `kmet.app.debug` installs its `log` on `enable!` and clears it on
   `disable!`, so the line goes to the same `--debug` file every other
   diagnostics line does.")

(defonce ^:private sink
  ;; The host's logger, or nil. `defonce` so a namespace reload does not drop
  ;; the sink the running composition installed.
  (atom nil))

(defn set-sink!
  "Install F as the sink `log!` calls with the logged parts. Returns nil."
  [f]
  (reset! sink f)
  nil)

(defn clear-sink!
  "Remove the installed sink; `log!` goes back to silent. Returns nil."
  []
  (reset! sink nil)
  nil)

(defn log!
  "Call the installed sink with PARTS, or do nothing when none is installed.
   A throwing sink is swallowed: a diagnostic must never break the caller."
  [& parts]
  (when-let [f @sink]
    (try
      (apply f parts)
      (catch Throwable _ nil)))
  nil)
