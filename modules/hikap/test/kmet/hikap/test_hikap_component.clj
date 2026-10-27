(ns kmet.hikap.test-hikap-component
  (:require [clojure.test :as t]
            [kmet.hikap.component :as c]))

(t/deftest new-store-shape
  (let [s (c/new-store)]
    (t/is (map? @s))
    (t/is (contains? @s :locals))
    (t/is (contains? @s :cleanups))
    (t/is (contains? @s :pass-sites))))

(t/deftest fetch-local-outside-store-throws
  (t/is (thrown? clojure.lang.ExceptionInfo (c/fetch-local 'site (fn [] 1)))))

(t/deftest fetch-local-initializes-once
  (let [s (c/new-store)
        init (atom 0)]
    (c/with-store s
      (c/begin-pass! s)
      (t/is (= 1 (c/fetch-local 'x (fn [] (swap! init inc) @init))))
      (t/is (= 1 @init)))
    (c/with-store s
      (c/begin-pass! s)
      (t/is (= 1 (c/fetch-local 'x (fn [] (swap! init inc) @init))))
      (t/is (= 1 @init)))))

(t/deftest fetch-local-different-sites
  (let [s (c/new-store)]
    (c/with-store s
      (c/begin-pass! s)
      (t/is (= 10 (c/fetch-local 'a (fn [] 10))))
      (t/is (= 20 (c/fetch-local 'b (fn [] 20)))))))

(t/deftest fetch-local-same-site-twice-in-one-pass-throws
  (let [s (c/new-store)]
    (c/with-store s
      (c/begin-pass! s)
      (c/fetch-local 'x (fn [] 1))
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (c/fetch-local 'x (fn [] 2)))))))

(t/deftest register-cleanup-once-per-site
  (let [s (c/new-store)
        calls (atom [])]
    (c/with-store s
      (c/begin-pass! s)
      (c/register-cleanup! 'site #(swap! calls conj :a))
      (c/register-cleanup! 'site #(swap! calls conj :b))
      (c/register-cleanup! 'other #(swap! calls conj :c)))
    (c/destroy-store! s)
    (t/is (= [:c :a] @calls) "LIFO: last registered first; duplicate site only once")))

(t/deftest fetch-local-throwing-init-stores-nothing
  (let [s (c/new-store)
        init (atom 0)]
    (c/with-store s
      (c/begin-pass! s)
      (t/is (thrown? Exception (c/fetch-local 'x (fn [] (swap! init inc) (throw (Exception. "boom"))))))
      (t/is (= 1 @init)))
    (c/with-store s
      (c/begin-pass! s)
      (t/is (= 2 (c/fetch-local 'x (fn [] (swap! init inc) @init)))))))

(t/deftest destroy-store-clears-and-isolates-cleanups
  (let [s (c/new-store)
        ok (atom false)]
    (c/with-store s
      (c/begin-pass! s)
      (c/register-cleanup! 'a #(throw (Exception. "boom")))
      (c/register-cleanup! 'b #(reset! ok true)))
    (c/destroy-store! s)
    (t/is @ok "subsequent cleanup still runs")
    (t/is (empty? (:cleanups @s)))
    (t/is (empty? (:locals @s)))))

(t/deftest with-let-macro-basic
  (let [s (c/new-store)
        init (atom 0)]
    (c/with-store s
      (c/begin-pass! s)
      (let [v (c/with-let [x (do (swap! init inc) @init)]
                x)]
        (t/is (= 1 v))
        (t/is (= 1 @init))))))

(t/deftest with-let-cleanup
  (let [s (c/new-store)
        calls (atom [])]
    (c/with-store s
      (c/begin-pass! s)
      (c/with-let [x 1]
        x
        (finally (swap! calls conj x))))
    (t/is (empty? @calls))
    (c/destroy-store! s)
    (t/is (= [1] @calls))))

(t/deftest frame-hook-schedules-without-throwing
  (let [calls (atom 0)]
    (c/set-frame-hook! #(swap! calls inc))
    (c/schedule-frame!)
    (t/is (= 1 @calls))
    (c/set-frame-hook! nil)
    (c/schedule-frame!)
    (t/is (= 1 @calls))))

(t/deftest track-render-caches-while-tracked-value-unchanged
  (let [comp {:cache-atom (atom nil)}
        a (atom 1)
        calls (atom 0)]
    (t/is (= 1 (c/track! comp 10 (do (swap! calls inc) @a))))
    (t/is (= 1 @calls))
    (t/is (= 1 (c/track! comp 10 (do (swap! calls inc) @a))))
    (t/is (= 1 @calls) "cache hit does not re-run body")
    (reset! a 2)
    (t/is (= 2 (c/track! comp 10 (do (swap! calls inc) @a))))
    (t/is (= 2 @calls))))

(t/deftest track-render-different-width-rebuilds
  (let [comp {:cache-atom (atom nil)}
        a (atom 1)
        calls (atom 0)]
    (c/track! comp 10 (do (swap! calls inc) @a))
    (c/track! comp 20 (do (swap! calls inc) @a))
    (t/is (= 2 @calls))))

(t/deftest invalidate-cache-forces-rebuild
  (let [comp {:cache-atom (atom nil)}
        a (atom 1)
        calls (atom 0)]
    (c/track! comp 10 (do (swap! calls inc) @a))
    (c/invalidate-cache comp)
    (c/track! comp 10 (do (swap! calls inc) @a))
    (t/is (= 2 @calls))))

(t/deftest live-watch-count-number
  (t/is (number? (c/live-watch-count))))