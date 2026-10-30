(ns clj-commons.pushback-stream-test
  (:require
   [clojure.test :refer :all]
   [clj-commons.byte-streams.pushback-stream :as p]
   [manifold.deferred :as d]))

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

(deftest test-reading-zero-bytes
  (let [in (byte-array (range 50))
        p
        ^clj_commons.byte_streams.pushback_stream.SynchronizedPushbackByteStream
        (p/pushback-stream 50)

        x (p/put-array p in 0 50)
        ary (byte-array 50)]
    (is (= 0 (.read p ary 25 0)))))

(deftest test-reading-zero-bytes-from-closed
  (let [in (byte-array (range 50))
        p
        ^clj_commons.byte_streams.pushback_stream.SynchronizedPushbackByteStream
        (p/pushback-stream 50)

        x (p/put-array p in 0 50)
        ary (byte-array 50)]
    (p/close p)
    (is (= 0 (.read p ary 25 0)))))

(def openjdk-input-stream-default-buffer-size 16384)

(deftest ^:java11 test-reading-closed-input-stream-exceeding-input-stream-default-buffer-size
  (let [c (* 2 openjdk-input-stream-default-buffer-size)
        l c
        in (byte-array (range l))
        p (p/pushback-stream c)
        s (p/->input-stream p)
        x (p/put-array p in 0 l)]
    (p/close p)
    (let [r (.readAllBytes ^java.io.InputStream s)]
      (is (= (count in) (count r)))
      (is (= (into [] in) (into [] r))))
    (is (= true @x))))

(deftest ^:java11 test-reading-asynchronically-closed-input-stream-exceeding-input-stream-default-buffer-size
  (let [c (* 2 openjdk-input-stream-default-buffer-size)
        l  c
        in (byte-array (range l))
        p (p/pushback-stream c)
        s (p/->input-stream p)
        x (p/put-array p in 0 l)]
    (d/on-realized x (fn [_] (p/close p)) (fn [_]))
    (let [r (.readAllBytes ^java.io.InputStream s)]
      (is (= (count in) (count r)))
      (is (= (into [] in) (into [] r))))
    (is (= true @x))))

(deftest ^:java11 test-reading-closed-input-stream-exceeding-input-stream-default-buffer-size-and-capacity
  (let [c (* 2 openjdk-input-stream-default-buffer-size)
        l (* 2 c)
        in (byte-array (range l))
        p (p/pushback-stream c)
        s (p/->input-stream p)
        x (p/put-array p in 0 l)]
    (p/close p)
    (let [r (.readAllBytes ^java.io.InputStream s)]
      (is (= (count in) (count r)))
      (is (= (into [] in) (into [] r))))
    (is (= true @x))))

(deftest ^:java11 test-reading-asynchronically-closed-input-stream-exceeding-input-stream-default-buffer-size-and-capacity
  (let [c (* 2 openjdk-input-stream-default-buffer-size)
        l (* 2 c)
        in (byte-array (range l))
        p (p/pushback-stream c)
        s (p/->input-stream p)
        x (p/put-array p in 0 l)]
    (d/on-realized x (fn [_] (p/close p)) (fn [_]))
    (let [r (.readAllBytes ^java.io.InputStream s)]
      (is (= (count in) (count r)))
      (is (= (into [] in) (into [] r))))
    (is (= true @x))))
