(ns byte-streams
  (:refer-clojure :exclude
    [object-array byte-array])
  (:require
    [byte-streams.char-sequence :as cs]
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
     RandomAccessFile
     Reader
     InputStreamReader
     BufferedReader]
    [java.nio.channels
     ReadableByteChannel
     WritableByteChannel
     FileChannel
     FileChannel$MapMode
     Channels
     Pipe]
    [java.nio.channels.spi
     AbstractSelectableChannel]))

;;; protocols

(defprotocol Byteable
  (to-byte [_] "Converts the object to a single byte."))

(defprotocol Closeable
  (close [_] "A protocol that is a superset of `java.io.Closeable`."))

(defprotocol ByteSource
  (take-bytes! [_ n options] "Takes `n` bytes from the byte source."))
 
(defprotocol ByteSink
  (send-bytes! [_ bytes options] "Puts `bytes` in the byte sink."))

;;;

(defonce ^:private src->dst->conversion (atom nil))
(defonce ^:private src->dst->transfer (atom nil))

(def ^:private object-array (class (clojure.core/object-array 0)))
(def ^:private byte-array (class (clojure.core/byte-array 0)))

(defn- protocol? [x]
  (and (map? x) (contains? x :on-interface)))

(defn seq-of? [x]
  (and (list? x) (= 'seq-of (first x))))

(defn seq-of [x]
  (list 'seq-of x))

(defn- abstract-type-descriptor [x]
  (if (seq-of? x)
    (seq-of (abstract-type-descriptor (second x)))
    (let [x (resolve x)
          x (if (var? x)
              @x
              x)]
      (if (protocol? x)
        (:var x)
        x))))

(defmacro def-conversion
  "Defines a conversion from one type to another."
  [[src dst :as conversion] params & body]
  (let [mt (meta conversion)
        src' (abstract-type-descriptor src)
        dst' (abstract-type-descriptor dst)]
    (swap! src->dst->conversion assoc-in [src' dst']
      (with-meta
        (eval
          `(fn [~(with-meta (first params)
                   {:tag (when-not (var? src')
                           (if (= byte-array src')
                             'bytes
                             src))})
                ~(if-let [options (second params)]
                   options
                   `_#)]
             ~@body))
        (merge
          mt
          {::conversion [src' dst']}))))
  nil)

(defmacro def-transfer
  "Defines a byte transfer from one type to another."
  [[src dst] params & body]
  (let [src' (abstract-type-descriptor src)
        dst' (abstract-type-descriptor dst)]
    (swap! src->dst->transfer assoc-in [src' dst']
      (eval
        `(fn [~(with-meta (first params) {:tag src})
              ~(with-meta (second params) {:tag dst})
              ~(if-let [options (get params 2)] options (gensym "options"))]
           ~@body)))))

;;; convert

(def ^:private ^:dynamic *searched* #{})

(defn- searched? [k dst]
  (*searched* [k dst]))

(defn- shortest [s]
  (->> s
    (sort-by
      #(->> %
         (map (fn [a+b]
                (-> (get-in @src->dst->transfer a+b
                      (when (every? seq-of? a+b)
                        (get-in @src->dst->conversion (map second a+b))))
                  meta
                  (get :cost 1))))
         (reduce +)))
    first))

(defn- class-satisfies? [protocol ^Class c]
  (boolean
    (or
      (.isAssignableFrom ^Class (:on-interface protocol) c)
      (some
        #(.isAssignableFrom ^Class c %)
        (keys (:impls protocol))))))

(defn- assignable? [a b]
  (let [a (if (var? a) @a a)
        b (if (var? b) @b b)]
    (cond
      (and (class? a) (class? b))
      (.isAssignableFrom ^Class b a)
      
      (and (protocol? b) (class? a))
      (class-satisfies? b a)
      
      (and (seq-of? a) (seq-of? b))
      (assignable? (second a) (second b))
      
      :else
      (= a b))))

(defn- valid-sources [x]
  (let [x (if (var? x) @x x)]
    (->> @src->dst->conversion
      keys
      (mapcat #(if (seq-of? %) [%] [% (seq-of %)]))
      (filter (partial assignable? x)))))

(defn- valid-destinations [x]
  (let [x (if (var? x) @x x)]
    (cond
      (seq-of? x)    (->> x second valid-destinations (map seq-of))
      (class? x)     [x]
      (protocol? x)  (keys (get x :impls))
      :else          [x])))

(defn- ^:dynamic shortest-conversion-path
  [curr dst]
  (if (assignable? curr dst)
    []
    (let [m @src->dst->conversion
          next (concat
                 ;; normal a -> b
                 (->> curr
                   valid-sources
                   (mapcat #(map list (repeat %) (keys (get m %)))))

                 ;; when there exists (a -> b), that implies (seq-of a) -> (seq-of b)
                 (when (seq-of? curr)
                   (->> curr
                     second
                     valid-sources
                     (mapcat #(map list (repeat (seq-of %)) (map seq-of (keys (get m %))))))))]
      (->> next
        (remove #(searched? (second %) dst))
        (map (fn [[from to]]
               (when-let [path (binding [*searched* (conj *searched* [curr dst])]
                                 (shortest-conversion-path to dst))]
                 (cons [from to] path))))
        (remove nil?)
        shortest))))

(defn conversion-path
  "Returns the path, if any, of type conversions that will transform `src` into `dst`, either
   of which may be a class, protocol, or `(seq-of class-or-protocol)`.  Each conversion is a
   pair of types, representing the source and destination."
  [src dst]
  (->> (for [src (valid-sources src), dst (valid-destinations dst)]
         [src dst])
    (map #(binding [shortest-conversion-path (memoize shortest-conversion-path)]
            (apply shortest-conversion-path %)))
    (remove nil?)
    shortest))

(defn- closeable-seq [s exhaustible? close-fn]
  (if (empty? s)
    (when exhaustible?
      (close-fn)
      nil)
    (reify

      java.io.Closeable
      (close [_]
        (close-fn))
      
      clojure.lang.Sequential
      clojure.lang.ISeq
      clojure.lang.Seqable
      (cons [_ a]
        (closeable-seq (cons a s) exhaustible? close-fn))
      (next [this]
        (closeable-seq (next s) exhaustible? close-fn))
      (more [this]
        (let [rst (next this)]
          (if (empty? rst)
            '()
            rst)))
      (first [_]
        (first s))
      (seq [this]
        this))))

(def ^:private converter
  (memoize
    (fn [src dst]
      ;; expand out the cartesian product of all possible starting and ending positions,
      ;; and choose the shortest
      (when-let [path (conversion-path src dst)]
        (let [fns (if (empty? path)
                    [(fn [x _] x)]
                    (map
                      (fn [[a b :as a+b]]
                        (if-let [f (get-in @src->dst->conversion a+b)]
                          f
                          
                          ;; implicit (seq-of a) -> (seq-of b) conversion
                          (if-let [f (when (every? seq-of? a+b)
                                       (get-in @src->dst->conversion (map second a+b)))]
                            (fn [x options]
                              (map #(f % options) x))
                            
                            ;; this shouldn't ever happen, but let's have a decent error message all the same
                            (throw
                              (IllegalStateException.
                                (str "We thought we could convert between " a " and " b ", but we can't."))))))
                      path))]
         (fn [x options]
           (let [close-fns (atom [])
                 result (reduce
                          (fn [x f]

                            ;; keep track of everything that needs to be closed once the bytes are exhausted
                            (when (satisfies? Closeable x)
                              (swap! close-fns conj #(close x)))
                            (f x options))
                          x
                          fns)]
             (if-let [close-fn (when-let [fns (seq @close-fns)]
                                 #(doseq [f fns] (f)))] 
               (if (sequential? result)
                 (closeable-seq result true close-fn)
                 (do
                   ;; we assume that if the end-result is closeable, it will take care of all the intermediate
                   ;; objects beneath it.  I think this is true as long as we're not doing multiple streaming
                   ;; reads, but this might need to be revisited.
                   (when-not (satisfies? Closeable result)
                     (close-fn))
                   result))
               result))))))))

(defn type-descriptor
  "Returns a descriptor that can be used with `conversion-path`."
  [x]
  (cond
    (or (class? x) (protocol? x))
    x

    (contains? @src->dst->conversion (class x))
    (class x)

    (or (sequential? x) (= object-array (class x)))
    (seq-of (type-descriptor (first x)))

    :else
    (class x)))

(defn convert
  "Converts `x`, if possible, into type `dst`, which can be either a class or protocol.  If no such conversion
   is possible, an IllegalArgumentException is thrown.

   `options` is a map, whose available settings depend on what sort of transform is being performed:

   `chunk-size` - if a stream is being transformed into a sequence of discrete chunks, `:chunk-size` describes the
                  size of the chunks, which default to 4096 bytes.

   `encoding`   - if a string is being encoded or decoded, `:encoding` describes the charset that is used, which
                  defaults to 'utf-8'

   `direct?`    - if a byte-buffer is being allocated, `:direct?` describes whether it should be a direct buffer,
                  defaulting to false"
  ([x dst]
     (convert x dst nil))
  ([x dst options]
     (when-not (or (nil? x) (and (sequential? x) (empty? x)))
       (let [src (type-descriptor x)
             dst (if (protocol? dst)
                   (:var dst)
                   dst)]
         (if (or
               (= src dst)
               (and (class? src) (class? dst) (.isAssignableFrom ^Class dst src)))
           x
           (if-let [f (converter src dst)]
             (f x options)
             (throw (IllegalArgumentException.
                      (if (seq-of? src)
                        (str "Don't know how to convert a sequence of " (second src) " into " dst)
                        (str "Don't know how to convert " src " into " dst))))))))))

(defn possible-conversions
  "Returns a list of all possible conversion targets from the initial value or class."
  [x]
  (let [sources (valid-sources (type-descriptor x))
        destinations (->> @src->dst->conversion
                       vals
                       (mapcat keys)
                       (concat (keys @src->dst->conversion))
                       distinct)]
    (->> destinations
      (concat
        (->> destinations
          (map #(when-not (seq-of? %) (seq-of %)))
          (remove nil?)))
      distinct
      (filter
        (fn [dst]
          (some
            #(conversion-path % dst)
            sources))))))

;;; transfer

(defn- default-transfer [source sink {:keys [chunk-size] :or {chunk-size 1024} :as options}]
  (loop []
    (when-let [b (take-bytes! source chunk-size options)]
      (send-bytes! sink b options)
      (recur))))

(def ^:private transfer-fn
  (memoize
    (fn this [src dst]
      (let [[src' dst'] (->> @src->dst->transfer
                          keys
                          (map (fn [src']
                                 (and
                                   (conversion-path src src')
                                   (when-let [dst' (some
                                                     #(and (conversion-path dst %) %)
                                                     (keys (@src->dst->transfer src')))]
                                     [(conversion-path src src') [src' dst']]))))
                          (sort-by (comp count first))
                          first
                          second)]
        (cond
          
          (and src' dst')
          (let [f (get-in @src->dst->transfer [src' dst'])]
            (fn [source sink options]
              (let [source' (convert source src')
                    sink' (convert sink dst')]
                (f source' sink' options)
                (doseq [x [source sink source' sink']]
                  (when (satisfies? Closeable x)
                    (close x))))))
          
          (and
            (conversion-path src ByteSource)
            (conversion-path dst ByteSink))
          (fn [source sink options]
            (let [source' (convert source ByteSource)
                  sink' (convert sink ByteSink)]
              (default-transfer source' sink' options)
              (doseq [x [source sink source' sink']]
                (when (satisfies? Closeable x)
                  (close x)))))
          
          :else
          nil)))))

;; for byte transfers
(defn transfer
  "Transfers, if possible, all bytes from `source` into `sink`.  If this cannot be accomplished, an IllegalArgumentException is
   thrown.

   `options` is a map whose available settings depends on the source and sink types:

   `chunk-size` - if a stream is being transformed into a sequence of discrete chunks, `:chunk-size` describes the
                  size of the chunks, which default to 4096 bytes.

   `encoding`   - if a string is being encoded or decoded, `:encoding` describes the charset that is used, which
                  defaults to 'utf-8'

   `append?`    - if a file is being written to, `:append?` determines whether the bytes will overwrite the existing content
                  or be appended to the end of the file.  This defaults to true."
  ([source sink]
     (transfer source sink nil))
  ([source sink options]
     (let [src (type-descriptor source)
           dst (type-descriptor sink)]
       (if-let [f (transfer-fn src dst)]
         (f source sink options)
         (if (seq-of? src)
           (throw (IllegalArgumentException. (str "Don't know how to transfer between a sequence of " (second src) " to " dst)))
           (throw (IllegalArgumentException. (str "Don't know how to transfer between " src " to " dst))))))))

(defn optimized-transfer?
  "Returns true if an optimized transfer function exists for the given source and sink objects."
  [type-descriptor sink-type]
  (boolean (transfer-fn type-descriptor sink-type)))

;;; conversion definitions

;; a sequence of numbers representing bytes => a lazy sequence of byte-buffers
(def-conversion [(seq-of Byteable) (seq-of ByteBuffer)]
  [s {:keys [chunk-size direct?] :or {chunk-size 4096, direct? false} :as options}]
  (when-not (empty? s)
    (let [s' (take chunk-size s)
          cnt (count s')
          buf (if direct?
                (ByteBuffer/allocateDirect cnt)
                (ByteBuffer/allocate cnt))]
      (doseq [[i x] (map list (range) s')]
        (.put buf i (to-byte x)))
      (cons
        buf
        (convert (drop cnt s) (seq-of ByteBuffer) options)))))

;; byte-array => byte-buffer
(def-conversion ^{:cost 0} [byte-array ByteBuffer]
  [ary]
  (ByteBuffer/wrap ary))

;; byte-array => direct-byte-buffer
(def-conversion [byte-array DirectByteBuffer]
  [ary]
  (let [len (Array/getLength ary)
        ^ByteBuffer buf (ByteBuffer/allocateDirect len)]
    (.put buf ary 0 len)
    (.position buf 0)
    buf))

;; byte-array => input-stream
(def-conversion ^{:cost 0} [byte-array InputStream]
  [ary]
  (ByteArrayInputStream. ary))

;; byte-buffer => byte-array
(def-conversion [ByteBuffer byte-array]
  [buf]
  (if (.hasArray buf)
    (if (= (alength (.array buf)) (.remaining buf))
      (.array buf)
      (let [ary (clojure.core/byte-array (.remaining buf))]
        (doto buf
          .mark
          (.get ary 0 (.remaining buf))
          .reset)
        ary))
    (let [^bytes ary (Array/newInstance Byte/TYPE (.remaining buf))]
      (doto buf .mark (.get ary) .reset)
      ary)))

;; sequence of byte-buffers => byte-buffer
(def-conversion [(seq-of ByteBuffer) ByteBuffer]
  [bufs {:keys [direct?] :or {direct? false}}]
  (if (and (empty? (rest bufs))
        (not (satisfies? Closeable bufs)))
    (first bufs)
    (let [len (reduce + (map #(.remaining ^ByteBuffer %) bufs))
          buf (if direct?
                (ByteBuffer/allocateDirect len)
                (ByteBuffer/allocate len))]
      (doseq [^ByteBuffer b bufs]
        (.mark b)
        (.put buf b)
        (.reset b))
      (when (satisfies? Closeable bufs)
        (close bufs))
      (.flip buf))))

;; byte-buffer => sequence of byte-buffers
(def-conversion ^{:cost 0} [ByteBuffer (seq-of ByteBuffer)]
  [buf {:keys [chunk-size]}]
  (if chunk-size
    (let [lim (.limit buf)
          indices (range (.position buf) lim chunk-size)]
      (map
        #(-> buf
           .duplicate
           (.position %)
           ^ByteBuffer (.limit (min lim (+ % chunk-size)))
           .slice)
        indices))
    [buf]))

;; channel => input-stream
(def-conversion ^{:cost 0} [ReadableByteChannel InputStream]
  [channel]
  (Channels/newInputStream channel))

;; channel => lazy-seq of byte-buffers
(def-conversion [ReadableByteChannel (seq-of ByteBuffer)]
  [channel {:keys [chunk-size direct?] :or {chunk-size 4096, direct? false} :as options}]
  (when (.isOpen channel)
    (lazy-seq
      (when-let [b (take-bytes! channel chunk-size options)]
        (cons b (convert channel (seq-of ByteBuffer) options))))))

;; input-stream => channel
(def-conversion ^{:cost 0} [InputStream ReadableByteChannel]
  [input-stream]
  (Channels/newChannel input-stream))

;; string => byte-array
(def-conversion [String byte-array]
  [s {:keys [encoding] :or {encoding "utf-8"}}]
  (.getBytes s (name encoding)))

;; byte-array => string
(def-conversion [byte-array String]
  [ary {:keys [encoding] :or {encoding "utf-8"}}]
  (String. ^bytes ary (name encoding)))

;; lazy-seq of byte-buffers => channel
(def-conversion [(seq-of ByteBuffer) ReadableByteChannel]
  [bufs]
  (let [pipe (Pipe/open)
        ^WritableByteChannel sink (.sink pipe)
        source (doto ^AbstractSelectableChannel (.source pipe)
                 (.configureBlocking true))]
    (future
      (loop [s bufs]
        (when (and (not (empty? s)) (.isOpen sink))
          (.write sink (first s))
          (recur (rest s))))
      (.close sink))
    source))

;; generic byte-source => lazy char-sequence
(def-conversion [ByteSource CharSequence]
  [source options]
  (cs/decode-byte-source
    #(let [bytes (take-bytes! source % options)]
       (convert bytes ByteBuffer options))
    #(when (satisfies? Closeable source)
       (close source))
    options))

;; input-stream => reader 
(def-conversion [InputStream Reader]
  [input-stream {:keys [encoding] :or {encoding "utf-8"}}]
  (BufferedReader. (InputStreamReader. input-stream ^String encoding)))

;; reader => char-sequence
(def-conversion [Reader CharSequence]
  [reader {:keys [chunk-size] :or {chunk-size 2048}}]
  (let [ary (char-array chunk-size)
        sb (StringBuilder.)]
    (loop []
      (let [n (.read reader ary 0 chunk-size)]
        (if (pos? n)
          (do
            (.append sb ary 0 n)
            (recur))
          sb)))))

;; char-sequence => string
(def-conversion [CharSequence String]
  [char-sequence]
  (.toString char-sequence))

;; sequence of strings => string
(def-conversion [(seq-of String) String]
  [strings]
  (let [sb (StringBuilder.)]
    (doseq [s strings]
      (.append sb s))
    (.toString sb)))

;; file => readable-channel
(def-conversion ^{:cost 0} [File ReadableByteChannel]
  [file]
  (.getChannel (FileInputStream. file)))

;; file => writable-channel
(def-conversion ^{:cost 0} [File WritableByteChannel]
  [file {:keys [append?] :or {append? true}}]
  (.getChannel (FileOutputStream. file (boolean append?))))

(def-conversion ^{:cost 0} [File (seq-of ByteBuffer)]
  [file {:keys [chunk-size writable?] :or {chunk-size (int 2e9), writable? false}}]
  (let [^RandomAccessFile raf (RandomAccessFile. file (if writable? "rw" "r"))
        ^FileChannel fc (.getChannel raf)
        buf-seq (fn buf-seq [offset]
                  (when-not (<= (.size fc) offset)
                    (let [remaining (- (.size fc) offset)]
                      (lazy-seq
                        (cons
                          (.map fc 
                            (if writable?
                              FileChannel$MapMode/READ_WRITE
                              FileChannel$MapMode/READ_ONLY)
                            offset
                            (min remaining chunk-size))
                          (buf-seq (+ offset chunk-size)))))))]
    (closeable-seq
      (buf-seq 0)
      false
      #(do
         (.close raf)
         (.close fc)))))

;; output-stream => writable-channel
(def-conversion ^{:cost 0} [OutputStream WritableByteChannel]
  [output-stream]
  (Channels/newChannel output-stream))

;; writable-channel => output-stream
(def-conversion ^{:cost 0} [WritableByteChannel OutputStream]
  [channel]
  (Channels/newOutputStream channel))

;;; def-transfers

(def-transfer [ReadableByteChannel File]
  [channel file {:keys [chunk-size] :or {chunk-size (int 1e7)} :as options}]
  (let [^FileChannel fc (convert file WritableByteChannel options)]
    (try
      (loop [idx 0]
        (let [n (.transferFrom fc channel idx chunk-size)]
          (when (pos? n)
            (recur (+ idx n)))))
      (finally
        (.close fc)))))

(def-transfer [File WritableByteChannel]
  [file channel {:keys [chunk-size] :or {chunk-size (int 1e6)} :as options}]
  (let [^FileChannel fc (convert file ReadableByteChannel options)]
    (try
      (loop [idx 0]
        (let [n (.transferTo fc channel idx chunk-size)]
          (when (pos? n)
            (recur (+ idx n)))))
      (finally
        (.close fc)))))

(def-transfer [InputStream OutputStream]
  [input-stream output-stream {:keys [chunk-size] :or {chunk-size 4096} :as options}]
  (let [ary (clojure.core/byte-array chunk-size)]
    (loop []
      (let [n (.read input-stream ary)]
        (when (pos? n)
          (.write output-stream ary 0 n)
          (.flush output-stream)
          (recur)))))) 

;;; protocol extensions

(extend-protocol Byteable

  Byte
  (to-byte [this] this)

  Short
  (to-byte [this] (byte this))

  Integer
  (to-byte [this] (byte this))

  Long
  (to-byte [this] (byte this)))

(extend-protocol ByteSink

  OutputStream
  (send-bytes! [this b _]
    (.write ^OutputStream this ^bytes (convert b bytes)))

  WritableByteChannel
  (send-bytes! [this b _]
    (.write this ^ByteBuffer (convert b ByteBuffer))))

(extend-protocol ByteSource

  InputStream
  (take-bytes! [this n _]
    (let [ary (clojure.core/byte-array n)
          n (long n)]
      (loop [idx 0]
        (if (== idx n)
          ary
          (let [read (.read this ary idx (long (- n idx)))]
            (if (== -1 read)
              (when (pos? idx)
                (let [ary' (clojure.core/byte-array idx)]
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

(extend-protocol Closeable

  java.io.Closeable
  (close [this] (.close this))

  )

;;; print-bytes

(let [special-character? (->> "' _-+=`~{}[]()\\/#@!?.,;\"" (map int) set)]
  (defn- readable-character? [x]
    (or
      (Character/isLetterOrDigit (int x))
      (special-character? (int x)))))

(defn print-bytes
  "Prints out the bytes in both hex and ASCII representations, 16 bytes per line."
  [bytes]
  (let [bufs (convert bytes (seq-of ByteBuffer) {:chunk-size 16})]
    (doseq [^ByteBuffer buf bufs]
      (let [s (convert buf String {:encoding "ascii"})
            bytes (repeatedly (min 16 (.remaining buf)) #(.get buf))
            padding (* 3 (- 16 (count bytes)))
            hex-format #(->> "%02X" (repeat %) (interpose " ") (apply str))]
        (println
          (apply format
            (str
              (hex-format (min 8 (count bytes)))
              "  "
              (hex-format (max 0 (- (count bytes) 8))))
            bytes)
          (apply str (repeat padding " "))
          "   "
          (->> s
            (map #(if (readable-character? %) % "."))
            (apply str)))))))

;;; to-* helpers

(defn ^ByteBuffer to-byte-buffer
  "Converts the object to a `java.nio.ByteBuffer`."
  ([x]
     (to-byte-buffer x nil))
  ([x options]
     (convert x ByteBuffer options)))

(defn ^ByteBuffer to-byte-buffers
  "Converts the object to a sequence of `java.nio.ByteBuffer`."
  ([x]
     (to-byte-buffers x nil))
  ([x options]
     (convert x (seq-of ByteBuffer) options)))

(defn ^"[B" to-byte-array
  "Converts the object to a byte-array."
  ([x]
     (to-byte-array x nil))
  ([x options]
     (convert x byte-array options)))

(defn ^InputStream to-input-stream
  "Converts the object to a `java.io.InputStream`."
  ([x]
     (to-input-stream x nil))
  ([x options]
     (convert x InputStream options)))

(defn ^CharSequence to-char-sequence
  "Converts to the object to a `java.lang.CharSequence`."
  ([x]
     (to-char-sequence x nil))
  ([x options]
     (convert x CharSequence options)))

(defn ^ReadableByteChannel to-readable-channel
  "Converts the object to a `java.nio.ReadableByteChannel`"
  ([x]
     (to-readable-channel x nil))
  ([x options]
     (convert x ReadableByteChannel options)))

(defn ^String to-string
  "Converts the object to a string."
  ([x]
     (to-string x nil))
  ([x options]
     (convert x String options)))

(defn to-line-seq
  "Converts the object to a lazy sequence of newline-delimited strings."
  ([x]
     (to-line-seq x nil))
  ([x options]
     (let [^Reader reader (convert x Reader options)]
       (closeable-seq (line-seq reader) true #(.close reader)))))

(defn to-byte-source
  "Converts the object to something that satisfies `ByteSource`."
  ([x]
     (to-byte-source x nil))
  ([x options]
     (convert x ByteSource options)))

(defn to-byte-sink
  "Converts the object to something that satisfies `ByteSink`."
  ([x]
     (to-byte-sink x nil))
  ([x options]
     (convert x ByteSink options)))
