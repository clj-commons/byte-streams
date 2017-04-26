(ns byte-streams-reload-test
  (:require
   [clojure.test :refer :all]))

(deftest test-reload-all
  (dotimes [_ 5]
    (require 'byte-streams :reload-all)))
