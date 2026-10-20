(ns kmet.libs.test-host
  "The host detector must match the runtime the tests actually run on —
   the same suite executes under `bb test` and `jolt test`."
  (:require [clojure.test :as t :refer [deftest is testing]]
            [kmet.libs.host :as host]))

(deftest detects-the-running-host
  (let [jolt? (some? (find-var 'clojure.core/*jolt-version*))
        babashka? (some? (System/getProperty "babashka.version"))]
    (testing "the predicates follow the markers this test reads directly"
      (is (= jolt? (host/jolt?)))
      (is (= babashka? (host/babashka?)))
      (is (= (and (not jolt?) (not babashka?)) (host/jvm?))))
    (testing "runtime-name and mark name the host the tests run on"
      (is (= (cond jolt? "jolt" babashka? "babashka" :else "clojure")
             (host/runtime-name)))
      (is (= (cond jolt? "ј" babashka? "б" :else "ц") (host/mark)))))
  (testing "the mark is the single Cyrillic letter badge of the runtime name"
    (is (contains? #{"б" "ј" "ц"} (host/mark))
        "babashka, jolt, and the Clojure JVM have Cyrillic badges")
    (is (= ({"jolt" "ј" "babashka" "б" "clojure" "ц"} (host/runtime-name))
           (host/mark))
        "the badge is the Cyrillic transliteration of the name's initial")
    (is (every? #(<= 0x0400 (int %) 0x04FF) (host/mark))
        "the badge is a Cyrillic letter, not a Latin one")))

(deftest runtime-name-and-mark-cover-the-jvm-host
  (with-redefs [host/jolt? (constantly false)
                host/babashka? (constantly false)]
    (is (true? (host/jvm?)))
    (is (= "clojure" (host/runtime-name)))
    (is (= "ц" (host/mark)) "the footer badges the JVM as Кц"))
  (with-redefs [host/jolt? (constantly true)
                host/babashka? (constantly false)]
    (is (false? (host/jvm?)))
    (is (= "jolt" (host/runtime-name)))
    (is (= "ј" (host/mark))))
  (with-redefs [host/jolt? (constantly false)
                host/babashka? (constantly true)]
    (is (false? (host/jvm?)))
    (is (= "babashka" (host/runtime-name)))
    (is (= "б" (host/mark)))))

(deftest stdout-tty?-answers-on-this-host
  (testing "the predicate is safe to call on either host — Jolt's
            System/console is permanently nil, so the native shim must
            actually resolve there"
    (is (boolean? (host/stdout-tty?))))
  (if (host/jolt?)
    (testing "the Jolt path is the native shim, not a swallowed load error"
      (is (some? (requiring-resolve 'kmet.libs.host-native/stdout-tty?))
          "kmet.libs.host-native resolves as a .jolt source"))
    (testing "babashka parity with System/console (the check stdout-tty? replaces)"
      (is (= (some? (System/console)) (host/stdout-tty?))))))

(deftest finite?-parity
  (is (true? (host/finite? 1)))
  (is (true? (host/finite? 1.5)))
  (is (true? (host/finite? 0)))
  (is (true? (host/finite? -3.25)))
  (is (true? (host/finite? 9e9)))
  (is (false? (host/finite? Double/NaN)) "NaN is not finite")
  (is (false? (host/finite? Double/POSITIVE_INFINITY)) "+Inf is not finite")
  (is (false? (host/finite? Double/NEGATIVE_INFINITY)) "-Inf is not finite")
  (is (false? (host/finite? nil)) "nil is not finite")
  (is (false? (host/finite? "1")) "strings are not finite")
  (when-not (some? (find-var 'clojure.core/*jolt-version*))
    (testing "parity with Double/isFinite on bb"
      (doseq [v [0 1 -1 1.5 -3.25 9e9 Double/NaN
                 Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]]
        (is (= (Double/isFinite (double v)) (host/finite? v))
            (str "parity for " v))))))

