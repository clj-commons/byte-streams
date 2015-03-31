(ns byte-streams-simple-check
  (:require
    [clojure.test :refer :all]
    [byte-streams :as bs]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.test.check.clojure-test :as ct :refer (defspec)]))

(defn sign [x]
  (cond
    (zero? x) 0
    (neg? x) -1
    :else 1))

(defspec equivalent-comparison 10000
  (prop/for-all [a gen/string-ascii , b gen/string-ascii]
    (= (sign (compare a b)) (sign (bs/compare-bytes a b)))))
