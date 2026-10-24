(ns kmet.app.modes.out
  "The modes' output discipline: one protocol record per line on a stream, one
   error line on stderr, nothing else.

   A mode writes the wire projection and its own verdict; everything a *run*
   says arrives as an event (and on the wire, if the format carries it). The
   `out` here is an explicit stream, not `*out*` read at call time: a mode's
   records are written from its reader thread and from its agent's loop thread,
   and the stream has to be the one the entry point opened for the run.")

(defn record!
  "Write RECORD as one EDN line on OUT. Locked on OUT: two threads write (the
   mode's reader, the loop that streams a run), and two records must never
   interleave on the wire."
  [out record]
  (locking out
    (binding [*out* out]
      (prn record)
      (flush))))

(defn error!
  "One line on stderr: the mode's own failure — a run's failure is an event
   the consumer already has, and repeating it here would be a second, worse
   copy of it."
  [message]
  (binding [*out* *err*]
    (println "Error:" message)
    (flush)))
