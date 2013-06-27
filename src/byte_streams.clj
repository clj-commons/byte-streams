(ns byte-streams
  (:refer-clojure :exclude [bytes objects])
  (:require
    [clojure.java.io :as io])
  (:import
    [java.nio
     ByteBuffer
     DirectByteBuffer]
    [java.lang.reflect
     Array]
    [java.io
     File
     FileOutputStream
     FileInputStream
     ByteArrayInputStream
     InputStream
     OutputStream
     Reader
     InputStreamReader
     BufferedReader]
    [java.nio.channels
     ReadableByteChannel
     WritableByteChannel
     Channels
     Pipe]
    [java.nio.channels.spi
     AbstractSelectableChannel]))

;;; protocols

(defprotocol Closeable
  (close [_]))

(defprotocol ByteSource
  (take-bytes! [_ n options] "Takes `n` bytes from the byte source."))

(defprotocol ByteSink
  (send-bytes! [_ bytes options] "Puts `bytes` in the byte sink."))

;;; utility functions for conversion graph

(def ^:private src->dst->conversion (atom nil))
(def ^:private src->dst->transfer (atom nil))

(def ^:private objects (class (object-array 0)))
(def ^:private bytes (class (byte-array 0)))

(defmacro def-conversion
  "Defines a conversion from one type to another."
  [[src dst] params & body]
  (let [src' (eval src)
        dst' (eval dst)]
    (swap! src->dst->conversion assoc-in [src' dst']
      (eval
        `(fn [~(with-meta (first params) {:tag src})
              ~(if-let [options (second params)]
                 options
                 `_#)]
           ~@body))))
  nil)

(defmacro def-transfer
  "Defines a byte transfer from one type to another."
  [[src dst] params & body]
  (let [src' (eval src)
        dst' (eval dst)]
    (swap! src->dst->conversion assoc-in [src' dst']
      (eval
        `(fn [~(with-meta (first params) {:tag src})
              ~(with-meta (second params) {:tag dst})
              ~@(if-let [rst (seq (drop 2 params))] rst (list '& (gensym "rest")))]
           ~@body)))))

(defn many [x]
  [:many x])

(defn many? [x]
  (and (vector? x) (= :many (first x))))

(defn protocol? [x]
  (and (map? x) (contains? x :impls)))
;;;

(def ^:private ^:dynamic *searched* #{})

(defn- searched? [k dst]
  (*searched* [k dst]))

(defn- shortest [s]
  (->> s (sort-by count) first))

(defn- shortest-conversion-path
  [curr dst]
  (if (= curr dst)
    [curr]
    (let [m @src->dst->conversion
          next (concat
                 ;; normal a -> b
                 (->> (m curr) keys)

                 ;; when exists (a -> b), (many a) can be implicitly converted to (many b)
                 (when (many? curr)
                   (->> (m (second curr)) keys (map many))))]
      (->> next
        (remove #(searched? % dst))
        (map (fn [n]
               (when-let [path (binding [*searched* (conj *searched* [curr dst])]
                                 (shortest-conversion-path n dst))]
                 (cons curr path))))
        (remove nil?)
        shortest))))

(defn- valid-sources [x]
  (let [x (if (var? x) @x x)]
    (->> @src->dst->conversion
      keys
      (filter #(cond
                 (and (class? x) (class? %))
                 (.isAssignableFrom ^Class % x)
                 
                 (and (many? x) (many? %))
                 (.isAssignableFrom ^Class (second %) (second x))
                 
                 :else
                 (= x %))))))

(defn- valid-destinations [x]
  (let [x (if (var? x) @x x)]
    (cond
      (many? x)      (->> x second valid-destinations (map many))
      (class? x)     [x]
      (protocol? x)  (keys (get x :impls))
      :else          [x])))

(def ^:private converter
  (memoize
    (fn [src dst]
      ;; expand out the cartesian product of all possible starting and ending positions,
      ;; and choose the shortest
      (let [path (->> (for [src (valid-sources src), dst (valid-destinations dst)]
                        [src dst])
                   (map #(apply shortest-conversion-path %))
                   (remove nil?)
                   shortest)
            fns  (->> path
                   (partition 2 1)
                   (map (fn [[a b :as a+b]]
                          (if-let [f (get-in @src->dst->conversion a+b)]
                            f
                            
                            ;; implicit (many a) -> (many b) conversion
                            (if-let [f (when (every? many? a+b)
                                         (get-in @src->dst->conversion (map second a+b)))]
                              (fn [x options]
                                (map #(f % options) x))
                              
                              ;; this shouldn't ever happen, but let's have a decent error message all the same
                              (throw (IllegalStateException. (str "We thought we could convert between " a " and " b ", but we can't."))))))))]
        (fn [x options]
          (reduce #(%2 %1 options) x fns))))))

(defn- source-type
  [x]
  (if (or (sequential? x) (= objects (class x)))
    (many (source-type (first x)))
    (class x)))

(defn convert
  "Converts `x`, if possible, into type `dst`, which can be either a class or protocol.  If no such conversion
   is possible, an IllegalArgumentException is thrown."
  ([x dst]
     (convert x dst nil))
  ([x dst options]
     (let [src (source-type x)]
       (if (or
             (= src dst)
             (and (class? src) (class? dst) (.isAssignableFrom ^Class dst src)))
         x
         (if-let [f (converter src dst)]
           (f x options)
           (throw (IllegalArgumentException.
                    (if (many? src)
                      (str "Don't know how to convert a sequence of " (second src) " into " dst)
                      (str "Don't know how to convert " src " into " dst)))))))))

(defn conversion-path
  "Returns the path, if any, of type conversion that will transform type `src`, which must be a class or (many class)
   into type `dst`, which can be either a class, a protocol, or (many class-or-protocol)."
  [src dst]
  (shortest-conversion-path src dst))

(defn possible-conversions
  [x]
  (let [src (if (class? x)
              x
              (source-type x))]
    (->> @src->dst->conversion
      vals
      (mapcat keys)
      distinct
      (filter #(conversion-path src %)))))

;; for byte transfers
(let [default-transfer (fn [source sink & {:keys [chunk-size] :or {chunk-size 1024} :as options}]
                         (loop []
                           (when-let [b (take-bytes! source chunk-size options)]
                             (send-bytes! sink b options)
                             (recur)))
                         (when (satisfies? Closeable source)
                           (close source))
                         (when (satisfies? Closeable sink)
                           (close sink)))

      transfer-fn (memoize
                    (fn [src dst]
                      (let [src' (->> @src->dst->transfer
                                   keys
                                   (map (partial conversion-path src))
                                   (remove nil?)
                                   shortest)
                            dst' (->> @src->dst->transfer
                                   vals
                                   (map (partial conversion-path dst))
                                   (remove nil?)
                                   shortest)]
                        (cond

                          (and src' dst')
                          (let [f (get-in @src->dst->transfer [src' dst'])]
                            (fn [source sink & options]
                              (apply f (convert source src') (convert sink dst') options)))

                          (and
                            (conversion-path src ByteSource)
                            (conversion-path dst ByteSink))
                          default-transfer

                          :else
                          nil))))]

  (defn transfer
    [source sink & options]
    (let [src (source-type source)
          dst (source-type sink)]
      (if-let [f (transfer-fn src dst)]
        (apply f source sink options)
        (if (many? src)
          (throw (IllegalArgumentException. (str "Don't know how to transfer between a sequence of " (second src) " to " dst)))
          (throw (IllegalArgumentException. (str "Don't know how to transfer between " src " to " dst))))))))

;;; conversion definitions

;; byte-array => byte-buffer
(def-conversion [bytes ByteBuffer]
  [ary]
  (ByteBuffer/wrap ary))

;; byte-array => direct-byte-buffer
(def-conversion [bytes DirectByteBuffer]
  [ary]
  (let [len (Array/getLength ary)
        ^ByteBuffer buf (ByteBuffer/allocateDirect len)]
    (.put buf ary 0 len)
    (.position buf 0)
    buf))

;; byte-array => input-stream
(def-conversion [bytes InputStream]
  [ary]
  (ByteArrayInputStream. ary))

;; byte-buffer => byte-array
(def-conversion [ByteBuffer bytes]
  [buf]
  (if (.hasArray buf)
    (if (= (.capacity buf) (.remaining buf))
      (.array buf)
      (let [ary (byte-array (.remaining buf))]
        (.get buf ary 0 (.remaining buf))
        ary))
    (let [^bytes ary (Array/newInstance Byte/TYPE (.remaining buf))]
      (doto buf .mark (.get ary) .reset)
      ary)))

;; sequence of byte-arrays => byte-buffer
(def-conversion [(many ByteBuffer) ByteBuffer]
  [bufs {:keys [direct?] :or {direct? false}}]
  (let [len (reduce + (map #(.remaining ^ByteBuffer %) bufs))
        buf (if direct?
              (ByteBuffer/allocateDirect len)
              (ByteBuffer/allocate len))]
    (doseq [^ByteBuffer b bufs]
      (.put buf b))
    (.flip buf)))

;; channel => input-stream
(def-conversion [ReadableByteChannel InputStream]
  [channel]
  (Channels/newInputStream channel))

;; channel => lazy-seq of byte-buffers
(def-conversion [ReadableByteChannel (many ByteBuffer)]
  [channel {:keys [chunk-size direct?] :or {chunk-size 4096, direct? false} :as options}]
  (when (.isOpen channel)
    (lazy-seq
      (when-let [b (take-bytes! channel chunk-size options)]
        (cons b (convert channel (many ByteBuffer) options))))))

;; input-stream => channel
(def-conversion [InputStream ReadableByteChannel]
  [input-stream]
  (Channels/newChannel input-stream))

;; string => byte-array
(def-conversion [String bytes]
  [s {:keys [encoding] :or {encoding "utf-8"}}]
  (.getBytes s (name encoding)))

;; byte-array => string
(def-conversion [bytes String]
  [ary {:keys [encoding] :or {encoding "utf-8"}}]
  (String. ary (name encoding)))

;; lazy-seq of byte-buffers => channel
(def-conversion [(many ByteBuffer) ReadableByteChannel]
  [bufs]
  (let [pipe (Pipe/open)
        ^WritableByteChannel sink (.sink pipe)
        source (doto ^AbstractSelectableChannel (.source pipe)
                 (.configureBlocking true))]
    (future
      (doseq [buf bufs]
        (.write sink buf))
      (.close sink))
    source))

;; input-stream => reader 
(def-conversion [InputStream Reader]
  [input-stream {:keys [encoding] :or {encoding "utf-8"}}]
  (BufferedReader. (InputStreamReader. input-stream ^String encoding)))

(def-conversion [Reader CharSequence]
  [reader]
  (let [ary (char-array 1024)
        sb (StringBuilder.)]
    (loop []
      (let [n (.read reader ary 0 1024)]
        (if (pos? n)
          (do
            (.append sb ary 0 n)
            (recur))
          sb)))))

(def-conversion [CharSequence String]
  [char-sequence]
  (.toString char-sequence))

(def-conversion [File ReadableByteChannel]
  [file]
  (.getChannel (FileInputStream. file)))

(def-conversion [File WritableByteChannel]
  [file {:keys [append?] :or {append? true}}]
  (.getChannel (FileOutputStream. file (boolean append?))))

;;;

(extend-protocol ByteSink

  OutputStream
  (send-bytes! [this b]
    (.write ^OutputStream this ^bytes (convert b bytes)))

  WritableByteChannel
  (send-bytes! [this b]
    (.write this ^ByteBuffer (convert b ByteBuffer))))

(extend-protocol ByteSource

  InputStream
  (take-bytes! [this n _]
    (let [ary (byte-array n)
          n (long n)]
      (loop [idx 0]
        (if (== idx n)
          ary
          (let [read (.read this ary idx (long (- n idx)))]
            (if (== -1 read)
              (when (pos? idx)
                (let [ary' (byte-array idx)]
                  (System/arraycopy ary 0 ary' 0 idx)
                  ary'))
              (recur (long (+ idx read)))))))))

  ReadableByteChannel
  (take-bytes! [this n {:keys [direct?] :or {direct? false}}]
    (when (.isOpen this)
      (let [^ByteBuffer buf (if direct?
                              (ByteBuffer/allocateDirect n)
                              (ByteBuffer/allocate n))]
        (while
          (and
            (.isOpen this)
            (pos? (.read this buf))))

        (when (pos? (.position buf))
          (.flip buf)))))

  ByteBuffer
  (take-bytes! [this n _]
    (when (pos? (.remaining this))
      (let [n (min (.remaining this) n)
            buf (-> this
                  .duplicate
                  ^ByteBuffer (.limit (+ (.position this) n))
                  ^ByteBuffer (.slice)
                  (.order (.order this)))]
        (.position this (+ n (.position this)))
        buf))))

;;;

(defn ^ByteBuffer to-byte-buffer
  "Converts the object to a java.nio.ByteBuffer."
  [x & options]
  (apply convert x ByteBuffer options))

(defn ^bytes to-byte-array
  "Converts the object to a byte-array."
  [x & options]
  (apply convert x bytes options))

(defn ^InputStream to-input-stream
  "Converts the object to an java.io.InputStream."
  [x & options]
  (apply convert x InputStream options))

(defn ^ReadableByteChannel to-channel
  "Converts the object to a java.nio.ReadableByteChannel"
  [x & options]
  (apply convert x ReadableByteChannel))

(defn to-byte-source
  "Converts the object to something that satisfies ByteSource."
  [x & options]
  (apply convert x ByteSource))




