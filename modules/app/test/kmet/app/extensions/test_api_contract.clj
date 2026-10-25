(ns kmet.app.extensions.test-api-contract
  "The extension ABI's half of the contract: every map read a `kmet.extension`
   wrapper performs on an extension's `api` must exist in the api the host
   builds.

   The ABI is the one thing installed extensions depend on, and it is a plain
   function table — a wrapper calling `(:notify (ui api))` on a host that has no
   `:notify` under `:ui` fails inside the extension, at the moment a user does
   something, with a nil-invoke. Nothing else checks the pair: the host's own
   tests exercise the capabilities they use, and an extension's tests exercise
   the ones it uses, so a gap between the two is invisible until it is somebody
   else's crash.

   The reads are taken from the ABI source, because that is the only place the
   whole surface is written down: `(:key api)` is a top-level read, `(:key
   (accessor api))` is a read under the accessor's own name (`:notify` under
   `:ui`, `:register-provider!` under `:models`)."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [kmet.app.extensions.core :as extensions]
            [kmet.test-utils :as tu]))

;; loading an extension writes into the host's registries, so the test binds one
(t/use-fixtures :each tu/with-extension-host)

(defn- abi-source []
  (or (some-> (io/resource "kmet/extension.clj") slurp)
      (throw (ex-info "kmet/extension.clj is not on the classpath" {}))))

(defn- api-reads
  "Every read the ABI performs on an api, as a set of paths."
  []
  (let [src (abi-source)]
    (into #{}
          (concat
           (map (comp vector keyword second)
                (re-seq #"\(:([a-zA-Z][a-zA-Z0-9?!-]*) +api\)" src))
           (for [[_ k accessor]
                 (re-seq #"\(:([a-zA-Z][a-zA-Z0-9?!-]*) +\(([a-zA-Z][a-zA-Z0-9?!-]*) +api\)\)" src)]
             [(keyword accessor) (keyword k)])))))

(t/deftest every-read-the-abi-makes-exists-in-the-api
  (extensions/clear-extensions!)
  (try
    (let [result (extensions/load-extension! "test/fixtures/ext-single/hello_ext.clj")]
      (t/is (nil? (:error result)) (str "fixture failed to load: " (:error result)))
      (let [api (extensions/extension-api (:extension result))
            reads (api-reads)]
        (t/is (map? api) "the host built an api for the loaded extension")
        (t/is (> (count reads) 30)
              "the extractor reads the ABI's surface, not a handful of lines")
        (t/is (seq (filter (comp #{:ui :models :session} first) reads))
              "the nested reads are in the sample")
        (let [missing (remove #(some? (get-in api %)) reads)]
          (t/is (empty? missing)
                (str "the api is missing " (pr-str (sort (map pr-str missing))))))))
    (finally
      (extensions/unload-all-extensions!))))
