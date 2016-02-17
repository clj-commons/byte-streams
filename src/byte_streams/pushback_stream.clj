(ns byte-streams.pushback-stream
  (:refer-clojure :exclude [take])
  (:require
    [primitive-math :as p]
    [byte-streams.utils :refer [doit]]
    [manifold
     [utils :as u]
     [stream :as s]
     [deferred :as d]]
    [clojure.walk :as walk])
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
  (put [^bytes x ^int offset ^int length])
  (put [^java.nio.ByteBuffer buf])
  (pushback [^bytes ary ^int offset ^int length])
  (pushback [^java.nio.ByteBuffer buf])
  (take [^bytes ary ^int offset ^int length ^boolean eager?])
  (^void close []))

(deftype Consumption
  [^ByteBuffer buf
   deferred
   ^boolean eager?])

(defn trigger [^Consumption c]
  (let [^ByteBuffer buf (.buf c)]
    (d/success! (.deferred c) (.position buf))))

(defn put [^ByteBuffer src ^ByteBuffer dst]
  (let [l (.limit src)]
    (.limit src (p/+ (.position src) (p/min (.remaining src) (.remaining dst))))
    (.put dst src)
    (.limit src l)))

(defn- expand-either [first? form]
  (let [form' (->> form
                (map
                  #(if (and (seq? %) (= 'either (first %)))
                     (nth % (if first? 1 2))
                     [%]))
                (apply concat))]
    (with-meta
      (if (seq? form)
        form'
        (into (empty form) form'))
      (meta form))))

(defn walk
  [inner outer form]
  (let [form' (cond
                (list? form) (outer (apply list (map inner form)))
                (seq? form) (outer (doall (map inner form)))
                (coll? form) (outer (into (empty form) (map inner form)))
                :else (outer form))]
    (if (instance? clojure.lang.IMeta form')
      (with-meta form' (meta form))
      form')))

(defn prewalk
  [f form]
  (walk (partial prewalk f) identity (f form)))

(defmacro ^:private both [body]
  `(do
     ~(prewalk
        (fn [x]
          (if (sequential? x)
            (expand-either true x)
            x))
        body)
     ~(prewalk
        (fn [x]
          (if (sequential? x)
            (expand-either false x)
            x))
        body)))

(both
  (deftype (either [PushbackByteStream] [SynchronizedPushbackByteStream])
    [lock
     ^LinkedList consumers
     ^long buffer-capacity
     ^:unsynchronized-mutable ^int buffer-size
     ^:unsynchronized-mutable deferred
     ^:unsynchronized-mutable closed?
     ^LinkedList buffer]

    InputStream$Streamable

    (available [_]
      buffer-size)

    (read [this]
      (let [ary (byte-array 1)
            len (long @(.take this ary 0 1 true))]
        (if (zero? len)
          -1
          (p/bit-and 0xFF (get ary 0)))))

    (read [this ary offset length]
      (let [n (long @(.take this ary offset length true))]
        (if (zero? n)
          -1
         n)))

   (skip [this n]
     @(.take this (byte-array n) 0 n true))

   PushbackStream

   (put [_ buf]

     (let [[consumers d]
           ((either
              [do]
              [u/with-lock* lock])

            (if closed?
              [nil
               (d/success-deferred false)]

              [(loop [acc []]
                 (if-let [^Consumption c (.peek consumers)]
                   (let [^ByteBuffer out (.buf c)]
                     (put buf out)
                     (when (or (.eager? c) (not (.hasRemaining out)))
                       (.remove consumers)
                       (recur (conj acc c))))
                   acc))

               (do
                 (when (.hasRemaining buf)
                   (.add buffer buf)
                   (set! buffer-size (unchecked-int (p/+ buffer-size (.remaining buf)))))

                 (cond

                   deferred
                   deferred

                   (p/<= buffer-size buffer-capacity)
                   (d/success-deferred true)

                   :else
                   (set! deferred (d/deferred))))]))]

       (when consumers
         (doit [c consumers]
           (trigger c)))

       d))

   (put [this ary offset length]
     (.put this
       (-> (ByteBuffer/wrap ary)
         (.position offset)
         (.limit (+ offset length)))))

   (pushback [_ buf]
     (let [consumers
           ((either
              [do]
              [u/with-lock* lock])
            (let [consumers
                  (loop [acc []]
                    (if-let [^Consumption c (.peek consumers)]
                      (let [^ByteBuffer out (.buf c)]
                        (put buf out)
                        (when (or (.eager? c) (not (.hasRemaining out)))
                          (.remove consumers)
                          (recur (conj acc c))))
                      acc))]

              (when (.hasRemaining buf)
                (.addLast buffer buf)
                (set! buffer-size (unchecked-int (p/+ buffer-size (.remaining buf)))))

              consumers))]

       (doit [c consumers]
         (trigger c))))

   (pushback [this ary offset length]
     (.pushback this
       (-> (ByteBuffer/wrap ary)
         (.position offset)
         (.limit (+ offset length)))))

   (take [_ ary offset length eager?]

     (let [out (-> (ByteBuffer/wrap ary)
                 (.position offset)
                 ^ByteBuffer (.limit (+ offset length))
                 .slice)

           [put take]

           ((either
              [do]
              [u/with-lock* lock])

            (loop []
              (when-let [^ByteBuffer in (.peek buffer)]
                (put in out)
                (when-not (.hasRemaining in)
                  (.remove buffer))
                (when (.hasRemaining out)
                  (recur))))

            (set! buffer-size (unchecked-int (p/- buffer-size (.position out))))

            [(when (and (p/<= buffer-size buffer-capacity) deferred)
               (let [d deferred]
                 (set! deferred nil)
                 d))

             (if (or closed?
                   (and (pos? (.position out))
                     (or eager? (not (.hasRemaining out)))))
               (d/success-deferred (.position out))
               (let [d (d/deferred)]
                 (.add consumers (Consumption. out d eager?))
                 d))])]

       (when put
         (d/success! put true))

       take))

   (close [_]
     (when ((either
              [do]
              [u/with-lock* lock])
            (when-not closed?
              (set! closed? true)
              true))
       (loop []
         (when-let [^Consumption c (.poll consumers)]
           (let [^ByteBuffer buf (.buf c)]
             (d/success! (.deferred c) (.position buf)))
           (recur))))

     true)))

(defn pushback-stream [capacity]
  (SynchronizedPushbackByteStream.
    (u/mutex)
    (LinkedList.)
    capacity
    0
    nil
    false
    (LinkedList.)))

(defn unsafe-pushback-stream [capacity]
  (PushbackByteStream.
    (u/mutex)
    (LinkedList.)
    capacity
    0
    nil
    false
    (LinkedList.)))

(def classname "byte_streams.pushback_stream.PushbackStream")

(definline put-array
  [p ary offset length]
  `(.put ~(with-meta p {:tag classname}) ~ary ~offset ~length))

(definline put-buffer
  [p buf]
  `(.put ~(with-meta p {:tag classname}) ~buf))

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
  `(.pushback ~(with-meta p {:tag classname}) ~buf))

(defn ->input-stream [pushback-stream]
  (InputStream. pushback-stream))
