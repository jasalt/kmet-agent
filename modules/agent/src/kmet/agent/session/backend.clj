(ns kmet.agent.session.backend
  "The EDNL session backend — `kmet.app.sessions`' `:sessionPersistence`
   contract over kmet1's store.

   A projection, not a copy: every op delegates to the namespace that already
   answers it (`store/create-session`, `store/load-session`, `store/append-entry`,
   `listing/list-sessions`, `listing/delete-session!`), so the file format,
   the torn-tail repair, the lazy first write and the branch chain stay where
   they are implemented. The handle is `kmet.agent.session.store/Session`; the
   app never looks inside it.

   The file lives in `agent` because it names `kmet.agent.session.*` (rank 4).
   It moves into `app` with the session layer — the module changes, the
   namespace does not (`new-app.md` § Two axes) — and then nothing here needs
   to change, because `kmet.app.sessions` sits at rank 2 below both."
  (:require [kmet.agent.session.listing :as listing]
            [kmet.agent.session.store :as store]))

(defn ednl
  "A fresh EDNL backend value. Stateless: the sessions it hands out carry the
   file, the entries and the mutation lock, exactly as kmet1's store built them."
  []
  {:create! (fn [{:keys [dir cwd parent-session]}]
              (store/create-session
               dir
               (cond-> {}
                 cwd (assoc :cwd cwd)
                 parent-session (assoc :parent-session parent-session))))
   :load (fn [path] (store/load-session path))
   :paths (fn [dir] (listing/list-sessions dir))
   :append! (fn [session entry] (store/append-entry session entry))
   :entries (fn [session] @(:entries session))
   :delete! (fn [session] (listing/delete-session! session))
   :header (fn [session] (:header session))})
