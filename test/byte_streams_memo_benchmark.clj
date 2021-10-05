(ns byte-streams-memo-benchmark
  (:require [metrics.core :refer [new-registry]]
            [metrics.timers :as mt]
            [byte-streams.utils :refer (fast-memoize)]
            ;; including the contrib memoize for completeness
            #_[clojure.core.memoize :as memo]))

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
(def test-fn-memo-memo (memo/memo test-fn-bare))
(def test-fn-memo-fifo (memo/fifo test-fn-bare :fifo/threshold 512))
(def test-fn-memo-lru (memo/lru test-fn-bare :lru/threshold 512))
(def test-fn-memo-ttl (memo/ttl test-fn-bare :ttl/threshold (* 20 60 1000)))
(def test-fn-memo-lu (memo/lu test-fn-bare :lu/threshold 512))



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

;;(bench-memo test-fn-memo-memo)
;; ==>
;;  {:write
;;   {0.5  1.03192489E8,
;;    0.75 1.04311908E8,
;;    0.95 1.04945046E8,
;;    0.99 1.05034738E8,
;;    1.0  1.0507147E8},
;;   :read
;;   {0.5  2095.0,
;;    0.75 2184.0,
;;    0.95 2496.0,
;;    0.99 9955.0,
;;    1.0  56349.0}}

;;(bench-memo test-fn-memo-fifo)
;; ==>
;;  {:write
;;   {0.5  1.03363339E8,
;;    0.75 1.04358253E8,
;;    0.95 1.04967234E8,
;;    0.99 1.05061748E8,
;;    1.0  1.05117065E8},
;;   :read
;;   {0.5  1493.0,
;;    0.75 1603.0,
;;    0.95 2060.0,
;;    0.99 23278.0,
;;    1.0  59612.0}}

;;(bench-memo test-fn-memo-lru)
;; ==>
;;  {:write
;;   {0.5  1.03048668E8,
;;    0.75 1.04225059E8,
;;    0.95 1.04972833E8,
;;    0.99 1.05092073E8,
;;    1.0  1.0516577E8},
;;   :read
;;   {0.5  6178.0,
;;    0.75 6720.0,
;;    0.95 9898.0,
;;    0.99 29697.0,
;;    1.0  173080.0}}

;;(bench-memo test-fn-memo-ttl)
;; ==>
;;  {:write
;;   {0.5  1.03293941E8,
;;    0.75 1.04178024E8,
;;    0.95 1.04943379E8,
;;    0.99 1.05085312E8,
;;    1.0  1.05156537E8},
;;   :read
;;   {0.5  2440.0,
;;    0.75 2593.0,
;;    0.95 3019.0,
;;    0.99 8545.0,
;;    1.0  61469.0}}

;;(bench-memo test-fn-memo-lu)
;; ==>
;;  {:write
;;   {0.5  1.03283018E8,
;;    0.75 1.04285354E8,
;;    0.95 1.04974472E8,
;;    0.99 1.05048606E8,
;;    1.0  1.051125E8},
;;   :read
;;   {0.5  127339.0,
;;    0.75 1.03311535E8,
;;    0.95 1.04816647E8,
;;    0.99 1.05044688E8,
;;    1.0  1.05131945E8}}


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

;;(bench-memo-p test-fn-memo-memo)
;; ==>
;;  {:write
;;   {0.5  1.02754166E8,
;;    0.75 1.04723716E8,
;;    0.95 1.04926705E8,
;;    0.99 1.05003105E8,
;;    1.0  1.05028287E8},
;;   :read
;;   {0.5  3318.0,
;;    0.75 5220.0,
;;    0.95 18893.0,
;;    0.99 27140.0,
;;    1.0  39161.0}}

;;(bench-memo-p test-fn-memo-fifo)
;; ==>
;;  {:write
;;   {0.5  1.01630378E8,
;;    0.75 1.03775283E8,
;;    0.95 1.04529737E8,
;;    0.99 1.04891561E8,
;;    1.0  1.0503139E8},
;;   :read
;;   {0.5  2559.0,
;;    0.75 3982.0,
;;    0.95 18104.0,
;;    0.99 49659.0,
;;    1.0  147896.0}}

;;(bench-memo-p test-fn-memo-lru)
;; ==>
;;  {:write
;;   {0.5  1.03548837E8,
;;    0.75 1.04497643E8,
;;    0.95 1.05005372E8,
;;    0.99 1.0508395E8,
;;    1.0  1.05209867E8},
;;   :read
;;   {0.5  74398.0,
;;    0.75 126207.0,
;;    0.95 265835.0,
;;    0.99 660098.0,
;;    1.0  975782.0}}

;;(bench-memo-p test-fn-memo-ttl)
;; ==>
;;  {:write
;;   {0.5  1.03458705E8,
;;    0.75 1.04241055E8,
;;    0.95 1.05006895E8,
;;    0.99 1.05067773E8,
;;    1.0  1.05094587E8},
;;   :read
;;   {0.5  6997.0,
;;    0.75 8538.0,
;;    0.95 15936.0,
;;    0.99 84489.0,
;;    1.0  97568.0}}

;;(bench-memo-p test-fn-memo-lu)
;; ==>
;;  {:write
;;   {0.5  1.02898682E8,
;;    0.75 1.04386849E8,
;;    0.95 1.05032741E8,
;;    0.99 1.05113373E8,
;;    1.0  1.05201505E8},
;;   :read
;;   {0.5  1.02711858E8,
;;    0.75 1.04287126E8,
;;    0.95 1.04990194E8,
;;    0.99 1.05036729E8,
;;    1.0  1.05046437E8}}
