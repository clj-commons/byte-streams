(ns byte-streams.protocols
  (:import
    [java.util.concurrent
     ConcurrentHashMap]))

(defprotocol Closeable
  (close [_] "A protocol that is a superset of `java.io.Closeable`."))

(defprotocol ByteSource
  (take-bytes! [_ n options] "Takes `n` bytes from the byte source."))

(defprotocol ByteSink
  (send-bytes! [_ bytes options] "Puts `bytes` in the byte sink."))

(extend-protocol Closeable

  java.io.Closeable
  (close [this] (.close this))

  )

(let [m (ConcurrentHashMap.)]
  (defn closeable? [x]
    (if (nil? x)
      false
      (let [c (class x)
            v (.get m c)]
        (if (nil? v)
          (let [v (satisfies? Closeable x)]
            (.put m c v)
            v)
          v)))))
