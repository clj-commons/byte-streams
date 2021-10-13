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


(defn test-fn-bare [a b] :ok)

(def test-fn-fast-memoize (fast-memoize test-fn-bare))
(def test-fn-core-memoize (memoize test-fn-bare))


;;;; RESULTS

;;; sequential

;; baseline

;;(bench-memo test-fn-bare)
;; ==>
{:write
 {0.5  230.0,
  0.75 337.0,
  0.95 595.0,
  0.99 1913.0,
  1.0  20945.0},
 :read
 {0.5  232.0,
  0.75 256.0,
  0.95 365.0,
  0.99 994.0,
  1.0  8095.0}}

;; memo functions

;;(bench-memo test-fn-fast-memoize)
;; ==>
{:write
 {0.5  104350.0,
  0.75 118843.0,
  0.95 178378.0,
  0.99 240377.0,
  1.0  651646.0},
 :read
 {0.5  35403.0,
  0.75 51430.0,
  0.95 66008.0,
  0.99 89549.0,
  1.0  203958.0}}

;;(bench-memo test-fn-core-memoize)
;; ==>
{:write
 {0.5  1393.0,
  0.75 1799.0,
  0.95 2475.0,
  0.99 6810.0,
  1.0  18687.0},
 :read
 {0.5  930.0,
  0.75 972.0,
  0.95 1133.0,
  0.99 1733.0,
  1.0  13050.0}}


;;; parallel

;; baseline

;;(bench-memo-p test-fn-bare)
;; ==>
{:write
 {0.5  634.0,
  0.75 770.0,
  0.95 1020.0,
  0.99 3975.0,
  1.0  23000.0},
 :read
 {0.5 578.0,
  0.75 800.0,
  0.95 1135.0,
  0.99 16407.0,
  1.0 53758.0}}

;; memo functions

;;(bench-memo-p test-fn-fast-memoize)
;; ==>
{:write
 {0.5  2538791.0,
  0.75 3211772.0,
  0.95 4209397.0,
  0.99 5344325.0,
  1.0  7526595.0},
 :read
 {0.5  108169.0,
  0.75 158425.0,
  0.95 211261.0,
  0.99 261031.0,
  1.0  400660.0}}

;;(bench-memo-p test-fn-core-memoize)
;; ==>
{:write
 {0.5  2114.0,
  0.75 3126.0,
  0.95 9092.0,
  0.99 20791.0,
  1.0  26334.0},
 :read
 {0.5  1296.0,
  0.75 1511.0,
  0.95 3700.0,
  0.99 18874.0,
  1.0  24161.0}}
