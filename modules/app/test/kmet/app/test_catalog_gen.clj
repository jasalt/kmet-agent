(ns kmet.app.test-catalog-gen
  "The committed composition catalog is the generator's output.

   Everything in `docs/composition.md` is derived — the built-in rows and the
   tool surface a minimal boot registers — so the file can only be wrong by
   being stale. This is the guard: `bb check-catalog` is the same call for a
   human, and the failure names the first divergent line."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [kmet.app.composition-gen :as gen]))

(deftest the-committed-catalog-is-current
  (let [problem (gen/check)]
    (is (nil? problem) (or problem ""))))

(deftest the-catalog-covers-every-shipped-profile-and-tool
  (let [doc (gen/markdown)]
    (doseq [name [":tui" ":print" ":rpc" ":server"]]
      (is (str/includes? doc (str "### `" name "`"))
          (str "the catalog documents " name)))
    (doseq [tool (map :name (gen/tool-records))]
      (is (str/includes? doc (str "### `" tool "`"))
          (str "and the tool the model is offered: " tool)))))
