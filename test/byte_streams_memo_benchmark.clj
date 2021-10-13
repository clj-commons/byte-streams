(ns byte-streams-memo-benchmark
  (:require [metrics.core :refer [new-registry]]
            [metrics.timers :as mt]
            [byte-streams.utils :refer (fast-memoize)]))

(defn bench-memo [f]
  (let [n 500
        warmup-arg1 (repeatedly n #(rand-int (* 20 n)))
        warmup-arg2 (repeatedly n #(rand-int (* 20 n)))
        test-arg1 (repeatedly n #(rand-int (* 20 n)))
        test-arg2 (repeatedly n #(rand-int (* 20 n)))

        reg (new-registry)
        t (mt/timer reg "throw-away")
        w (mt/timer reg "write")
        r (mt/timer reg "read")
        p [0.50 0.75 0.95 0.99 1.00]

        ;; warmup the jit
        _ (doall
           (map (fn [a b] (mt/time! t (f a b)))
                warmup-arg1
                warmup-arg2))

        ;; benchmark
        _ (doall
           (map (fn [a b] (mt/time! w (f a b)))
                test-arg1
                test-arg2))
        _ (doall
           (map (fn [a b] (mt/time! r (f a b)))
                test-arg1
                test-arg2))]
    {:write (mt/percentiles w p)
     :read (mt/percentiles r p)}))

(defn bench-memo-p [f]
  (let [n 500
        warmup-arg1 (repeatedly n #(rand-int (* 20 n)))
        warmup-arg2 (repeatedly n #(rand-int (* 20 n)))
        test-arg1 (repeatedly n #(rand-int (* 20 n)))
        test-arg2 (repeatedly n #(rand-int (* 20 n)))

        reg (new-registry)
        t (mt/timer reg "throw-away")
        w (mt/timer reg "write")
        r (mt/timer reg "read")
        p [0.50 0.75 0.95 0.99 1.00]

        ;; warmup the jit
        _ (doall
           (pmap (fn [a b] (mt/time! t (f a b)))
                warmup-arg1
                warmup-arg2))

        ;; benchmark
        _ (doall
           (pmap (fn [a b] (mt/time! w (f a b)))
                test-arg1
                test-arg2))
        _ (doall
           (pmap (fn [a b] (mt/time! r (f a b)))
                test-arg1
                test-arg2))]
    {:write (mt/percentiles w p)
     :read (mt/percentiles r p)}))

(defn test-fn-bare [a b] (Thread/sleep 100) (+ a a b b))

(def test-fn-fast-memoize (fast-memoize test-fn-bare))
(def test-fn-core-memoize (memoize test-fn-bare))



;;;; RESULTS

;;; sequential

;; baseline

;;(bench-memo test-fn-bare)
;; ==>
;;  {:write
;;   {0.5  1.03282649E8,
;;    0.75 1.04279945E8,
;;    0.95 1.04914732E8,
;;    0.99 1.05022515E8,
;;    1.0  1.05056833E8},
;;   :read
;;   {0.5  1.03312014E8,
;;    0.75 1.04309255E8,
;;    0.95 1.04935589E8,
;;    0.99 1.0502147E8,
;;    1.0  1.05072024E8}}


;; memo functions

;;(bench-memo test-fn-fast-memoize)
;; ==>
;;  {:write
;;   {0.5  1.03802839E8,
;;    0.75 1.04731673E8,
;;    0.95 1.0531916E8,
;;    0.99 1.05490683E8,
;;    1.0  1.05798693E8},
;;   :read
;;   {0.5  61226.0,
;;    0.75 92478.0,
;;    0.95 120656.0,
;;    0.99 150084.0,
;;    1.0  247345.0}}

;;(bench-memo test-fn-core-memoize)
;; ==>
;;  {:write
;;   {0.5  1.03252352E8,
;;    0.75 1.0424466E8,
;;    0.95 1.0489142E8,
;;    0.99 1.05013221E8,
;;    1.0  1.0506257E8},
;;   :read
;;   {0.5  590.0,
;;    0.75 644.0,
;;    0.95 889.0,
;;    0.99 1402.0,
;;    1.0  10257.0}}


;;; parallel

;; baseline

;;(bench-memo test-fn-bare)
;; ==>
;;  {:write
;;   {0.5  1.03375904E8,
;;    0.75 1.04397738E8,
;;    0.95 1.0495538E8,
;;    0.99 1.05019825E8,
;;    1.0  1.05053409E8},
;;   :read
;;   {0.5  1.03126988E8,
;;    0.75 1.04244516E8,
;;    0.95 1.04858271E8,
;;    0.99 1.04993598E8,
;;    1.0  1.05042005E8}}


;; memo functions

;;(bench-memo-p test-fn-fast-memoize)
;; ==>
;;  {:write
;;   {0.5  1.04245465E8,
;;    0.75 1.05234969E8,
;;    0.95 1.06596286E8,
;;    0.99 1.07800109E8,
;;    1.0  1.0788091E8},
;;   :read
;;   {0.5  151151.0,
;;    0.75 222120.0,
;;    0.95 280444.0,
;;    0.99 324406.0,
;;    1.0  442321.0}}

;;(bench-memo-p test-fn-core-memoize)
;; ==>
;;  {:write
;;   {0.5  1.03180325E8,
;;    0.75 1.03930979E8,
;;    0.95 1.04950767E8,
;;    0.99 1.04980784E8,
;;    1.0  1.05008934E8},
;;   :read
;;   {0.5  952.0,
;;    0.75 1083.0,
;;    0.95 2896.0,
;;    0.99 77887.0,
;;    1.0  106097.0}}
