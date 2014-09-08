(ns pushback-stream-test
  (:require
    [clojure.test :refer :all]
    [byte-streams.pushback-stream :as p]))

(def in (byte-array (range 100)))

(deftest test-pushback-stream
  (let [p (p/pushback-stream 50)
        x (p/put-array p in 0 100)
        ary (byte-array 50)]
    (is (= 50 @(p/take p ary 0 50)))
    (is (= (range 50) (seq ary)))
    (is (= true @x))
    (is (= 50 @(p/take p ary 0 50)))
    (is (= (range 50 100) (seq ary)))

    (p/pushback-array p in 50 50)
    (is (= 50 @(p/take p ary 0 50)))
    (is (= (range 50 100) (seq ary)))))
