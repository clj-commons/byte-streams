(ns clj-commons.byte-streams-reload-test
  (:require
   [clojure.test :refer :all]))

#_(deftest test-reload-all
  (dotimes [_ 5]
    (require 'clj-commons.byte-streams :reload-all)))
