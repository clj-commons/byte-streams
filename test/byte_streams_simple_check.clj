(ns byte-streams-simple-check
  (:require
    [clojure.test :refer :all]
    [byte-streams :as bs]
    [simple-check.core :as sc]
    [simple-check.generators :as gen]
    [simple-check.properties :as prop]
    [simple-check.clojure-test :as ct :refer (defspec)]))

(defn sign [x]
  (cond
    (zero? x) 0
    (neg? x) -1
    :else 1))

(defspec equivalent-comparison 10000
  (prop/for-all [a gen/string-ascii , b gen/string-ascii]
    (= (sign (compare a b)) (sign (bs/compare-bytes a b)))))
