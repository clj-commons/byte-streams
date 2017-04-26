(ns reload_test
  (:require [byte-streams]
            [clojure.test :refer :all]))

(deftest test-reload
  (dotimes [_ 5]
    (use 'byte-streams :reload-all)))
