(ns kmet.app.test-extensions
  (:require [clojure.test :refer [deftest is]]
            [kmet.agent.extensions.core :as extensions]
            [kmet.app.extensions :as row]
            [kmet.srce.core :as srce]))

(defn- mounted
  "A root with the host's row mounted, as the composition mounts it."
  []
  (let [root (srce/root {:name :app})
        fiber (srce/plugin! root row/plugin)]
    {:root root :fiber fiber}))

(deftest the-row-binds-the-host-and-provides-its-handle
  (try
    (let [{:keys [root]} (mounted)]
      (is (some? (srce/get root :extensions)) "the handle is a service")
      (is (= [] (extensions/get-tool-call-hooks)) "and the host answers: no hooks yet")
      ;; the host's own tags land in the handle this row provides — the thing
      ;; that makes the binding worth a row, since a run's hooks resolve here
      (extensions/register-tool-call-hook! :owner/fn (fn [_] nil))
      (is (= 1 (count (extensions/get-tool-call-hooks)))
          "a registration the host accepted is one its bound handle holds")
      (is (= :kmet/no-resource-registries
             (try (extensions/resources :skills)
                  nil
                  (catch Exception e (:type (ex-data e)))))
          "and a contribution this owner bound no registries for says so"))
    (finally (extensions/bind-services! nil))))

(deftest a-second-composition-puts-the-first-binding-back
  ;; The binding is process-wide and one owner at a time: mounting replaces it,
  ;; disposing puts back what was there, so a composition that goes away cannot
  ;; leave the host pointing at a root nothing owns.
  (try
    (let [{:keys [fiber]} (mounted)]
      (extensions/register-tool-call-hook! :owner/first (fn [_] nil))
      (let [{second :fiber} (mounted)]
        (is (= [] (extensions/get-tool-call-hooks))
            "the second mount binds its own handle")
        (srce/dispose! second)
        (is (= 1 (count (extensions/get-tool-call-hooks)))
            "and disposing it restores the one it replaced"))
      (srce/dispose! fiber))
    (finally
      (extensions/bind-services! nil)
      (is (= :kmet/no-host-services
             (try (extensions/get-tool-call-hooks)
                  nil
                  (catch Exception e (:type (ex-data e)))))
          "unbound is a named error, not a nil answer"))))
