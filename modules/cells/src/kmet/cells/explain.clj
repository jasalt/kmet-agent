(ns kmet.cells.explain
  "The human half of the snapshot: `composition` is the ABI, this is the
   string `/units` prints (cells.md §7, §12 Q4)."
  (:require [clojure.string :as str]))

(defn- slot-line
  "`owner slot handle=… config=… keys=… metadata…`, absent parts omitted;
   metadata is printed verbatim with its own key."
  [slot entry]
  (str/join
   " "
   (concat
    [(pr-str (:owner entry)) (pr-str slot)]
    (when-let [handle (:handle entry)]
      [(str "handle=" (pr-str handle))])
    (when (contains? entry :config)
      [(str "config=" (pr-str (:config entry)))])
    (when-let [keys (:keys entry)]
      [(str "keys=" (pr-str keys))])
    (for [[k v] (dissoc entry :owner :handle :config :keys)]
      (str (pr-str k) "=" (pr-str v))))))

(defn explain
  "Render a snapshot for a human: one line per slot, in owner-path order.
   Cosmetic and free to change; `composition` is the stable form."
  [snapshot]
  (str/join "\n"
            (map (fn [[slot entry]] (slot-line slot entry))
                 (sort-by (comp :owner val) snapshot))))
