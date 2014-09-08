(ns byte-streams.pushback-stream
  (:refer-clojure :exclude [take])
  (:require
    [primitive-math :as p]
    [manifold
     [utils :as u]
     [stream :as s]
     [deferred :as d]])
  (:import
    [java.nio
     ByteBuffer]
    [byte_streams
     InputStream
     InputStream$Streamable]
    [java.util
     LinkedList
     ArrayDeque]))

(set! *unchecked-math* true)

(definterface PushbackStream
  (put [x ^int offset ^int length])
  (pushback [^bytes ary ^int offset ^int length])
  (take [^bytes ary ^int offset ^int length ^boolean eager?])
  (^void close []))

(deftype Consumption
  [^ByteBuffer buf
   deferred
   ^boolean eager?])

(defn put [^ByteBuffer src ^ByteBuffer dst]
  (let [l (.limit src)]
    (.limit src (p/+ (.position src) (p/min (.remaining src) (.remaining dst))))
    (.put dst src)
    (.limit src l)))

(deftype PushbackByteStream
  [
   lock
   ^LinkedList consumers
   ^long buffer-capacity
   ^:unsynchronized-mutable ^int buffer-size
   ^:unsynchronized-mutable deferred
   ^:unsynchronized-mutable closed?
   ^LinkedList buffer
   ]

  InputStream$Streamable

  (available [_]
    buffer-size)

  (read [this]
    (let [ary (byte-array 1)
          len @(.take this ary 0 1 true)]
      (if (zero? len)
        -1
        (p/bit-and 0xFF (get ary 0)))))

  (read [this ary offset length]
    (let [n @(.take this ary offset length true)]
      (if (zero? n)
        -1
        n)))

  (skip [this n]
    @(.take this (byte-array n) 0 n true))

  PushbackStream

  (put [_ x offset length]
    (let [^ByteBuffer
          in (if (instance? ByteBuffer x)
               (-> ^ByteBuffer x .duplicate .slice)
               (-> (ByteBuffer/wrap x)
                 (.position offset)
                 ^ByteBuffer (.limit (+ offset length))
                 .slice))]
      (u/with-lock lock
        (if closed?
          (d/success-deferred false)

          (do
            (loop []
              (when-let [^Consumption c (.peek consumers)]
                (let [^ByteBuffer out (.buf c)]
                  (put in out)
                  (when (or (.eager? c) (not (.hasRemaining out)))
                    (.remove consumers)
                    (d/success! (.deferred c) (.position out))
                    (recur)))))

            (when (.hasRemaining in)
              (.add buffer in)
              (set! buffer-size (unchecked-int (p/+ buffer-size (.remaining in)))))

            (cond

              deferred
              deferred

              (p/<= buffer-size buffer-capacity)
              (d/success-deferred true)

              :else
              (set! deferred (d/deferred))))))))

  (pushback [_ x offset length]
    (let [^ByteBuffer
          in (if (instance? ByteBuffer x)
               (-> ^ByteBuffer x .duplicate .slice)
               (-> (ByteBuffer/wrap x)
                 (.position offset)
                 ^ByteBuffer (.limit (+ offset length))
                 .slice))]
      (u/with-lock lock
        (loop []
          (when-let [^Consumption c (.peek consumers)]
            (let [^ByteBuffer out (.buf c)]
              (put out in)
              (when (or (.eager? c) (not (.hasRemaining out)))
                (.remove consumers)
                (d/success! (.deferred c) (.position out))
                (recur)))))

        (when (.hasRemaining in)
          (.addLast buffer in)
          (set! buffer-size (unchecked-int (p/+ buffer-size (.remaining in))))))))

  (take [_ ary offset length eager?]
    (let [out (-> (ByteBuffer/wrap ary)
                (.position offset)
                ^ByteBuffer (.limit (+ offset length))
                .slice)]
      (u/with-lock lock
        (do

          (loop []
            (when-let [^ByteBuffer in (.peek buffer)]
              (put in out)
              (when-not (.hasRemaining in)
                (.remove buffer))
              (when (.hasRemaining out)
                (recur))))

          (set! buffer-size (unchecked-int (p/- buffer-size (.position out))))

          (when (and (p/<= buffer-size buffer-capacity) deferred)
            (d/success! deferred true)
            (set! deferred nil))

          (if (or closed?
                (and (pos? (.position out))
                  (or eager? (not (.hasRemaining out)))))
            (d/success-deferred (.position out))
            (let [d (d/deferred)]
              (.add consumers (Consumption. out d eager?))
              d))))))

  (close [_]
    (u/with-lock lock
      (set! closed? true)
      (loop []
        (when-let [^Consumption c (.poll consumers)]
          (d/success! (.deferred c) (.position ^ByteBuffer (.buf c)))
          (recur)))
      true)))

(defn pushback-stream [capacity]
  (PushbackByteStream.
    (u/mutex)
    (LinkedList.)
    capacity
    0
    nil
    false
    (LinkedList.)))

(def classname "byte_streams.pushback_stream.PushbackByteStream")

(definline put-array
  [p ary offset length]
  `(.put ~(with-meta p {:tag classname}) ~ary ~offset ~length))

(definline put-buffer
  [p buf]
  `(.put ~(with-meta p {:tag classname}) ~buf 0 0))

(definline close [p]
  `(.close ~(with-meta p {:tag classname})))

(definline eager-take
  [p ary offset length]
  `(.take ~(with-meta p {:tag classname}) ~ary ~offset ~length true))

(definline take
  [p ary offset length]
  `(.take ~(with-meta p {:tag classname}) ~ary ~offset ~length false))

(definline pushback-array
  [p ary offset length]
  `(.pushback ~(with-meta p {:tag classname}) ~ary ~offset ~length))

(definline pushback-buffer
  [p buf]
  `(.pushback ~(with-meta p {:tag classname}) ~buf 0 0))

(defn ->input-stream [pushback-stream]
  (InputStream. pushback-stream))
