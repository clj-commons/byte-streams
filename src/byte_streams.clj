(ns byte-streams
  (:refer-clojure :exclude [byte-array vector-of])
  (:require
    [manifold
     [stream :as s]
     [deferred :as d]]
    [byte-streams
     [graph :as g]
     [protocols :as proto]
     [pushback-stream :as ps]
     [char-sequence :as cs]
     [utils :refer (fast-memoize)]]
    [clojure.java.io :as io]
    [primitive-math :as p])
  (:import
    [byte_streams
     Utils
     ByteBufferInputStream]
    [byte_streams.graph
     Type]
    [java.nio
     ByteBuffer
     DirectByteBuffer]
    [java.lang.reflect
     Array]
    [java.util.concurrent.atomic
     AtomicBoolean]
    [java.io
     File
     FileOutputStream
     FileInputStream
     ByteArrayInputStream
     ByteArrayOutputStream
     PipedOutputStream
     PipedInputStream
     DataInputStream
     InputStream
     OutputStream
     IOException
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

;;;

(defonce conversions (atom (g/conversion-graph)))
(defonce inverse-conversions (atom (g/conversion-graph)))
(defonce src->dst->transfer (atom nil))

(def ^:private ^:const byte-array (class (Utils/byteArray 0)))

(defn seq-of [x]
  (g/type 'seq (if (identical? bytes x) byte-array x)))

(defn stream-of [x]
  (g/type 'stream (if (identical? bytes x) byte-array x)))

(defn vector-of [x]
  (g/type 'vector (if (identical? bytes x) byte-array x)))

(defn type-descriptor
  "Returns a descriptor of the type of the given instance."
  [x]
  (cond

    (nil? x)
    (g/type ::nil)

    (identical? bytes x)
    (g/type byte-array)

    (vector? x)
    (vector-of (.type ^Type (type-descriptor (first x))))

    (sequential? x)
    (seq-of nil)

    (s/source? x)
    (stream-of nil)

    :else
    (g/type (class x))))

(defn- normalize-type-descriptor [x]
  (cond
    (instance? Type x)
    x

    (or (= 'bytes x) (= bytes x))
    (g/type byte-array)

    :else
    (g/type (eval x))))

(defmacro def-conversion
  "Defines a conversion from one type to another."
  [[src dst :as conversion] params & body]
  (let [^Type src (normalize-type-descriptor src)
        dst (normalize-type-descriptor dst)]
    `(let [f#
           (fn [~(with-meta (first params)
                   {:tag (when (and (instance? Class (.type src)) (not (.wrapper src)))
                           (if (= src (normalize-type-descriptor 'bytes))
                             'bytes
                             (.getName ^Class (.type src))))})
                ~(if-let [options (second params)]
                   options
                   `_#)]
             ~@body)

           cost#
           ~(get (meta conversion) :cost 1)]
       (swap! conversions g/assoc-conversion ~src ~dst f# cost#)
       (swap! inverse-conversions g/assoc-conversion ~dst ~src f# cost#))))

(defmacro def-transfer
  "Defines a byte transfer from one type to another."
  [[src dst] params & body]
  (let [src (normalize-type-descriptor src)
        dst (normalize-type-descriptor dst)]
    `(swap! src->dst->transfer assoc-in [~src ~dst]
       (fn [~(with-meta (first params) {:tag src})
            ~(with-meta (second params) {:tag dst})
            ~(if-let [options (get params 2)] options (gensym "options"))]
         ~@body))))

;;; convert

(def ^:private converter
  (fast-memoize
    (fn [src dst]
      (g/conversion-fn @conversions src dst))))

(declare convert)

(def ^:private seq-converter
  (fast-memoize
    (fn [dst]
      (g/seq-conversion-fn @conversions convert 'seq dst))))

(def ^:private stream-converter
  (fast-memoize
    (fn [dst]
      (g/seq-conversion-fn @conversions convert 'stream dst))))

(defn conversion-path [src dst]
  (let [path (-> @conversions
               (g/conversion-path (g/type src) (g/type dst))
               :path)]
    (map (partial mapv g/pprint-type) path)))

(defn convert
  "Converts `x`, if possible, into type `dst`, which can be either a class or protocol.  If no such conversion
   is possible, an IllegalArgumentException is thrown.  If `x` is a stream, then the `src` type must be explicitly specified.

   `options` is a map, whose available settings depend on what sort of transform is being performed:

   `chunk-size` - if a stream is being transformed into a sequence of discrete chunks, `:chunk-size` describes the
                  size of the chunks, which default to 4096 bytes.

   `encoding`   - if a string is being encoded or decoded, `:encoding` describes the charset that is used, which
                  defaults to 'UTF-8'

   `direct?`    - if a byte-buffer is being allocated, `:direct?` describes whether it should be a direct buffer,
                  defaulting to false"
  ([x dst]
     (convert x dst nil))
  ([x dst options]
     (let [dst (g/type dst)
           source-type (get options :source-type)
           ^Type
           src (g/type
                 (or source-type
                   (type-descriptor x)))
           wrapper (.wrapper src)]

       (cond

         (not (nil? (.type src)))
         (if-let [f (or
                      (converter src dst)
                      (converter (g/type (class x)) dst))]
           (f x (if source-type (dissoc options :source-type) options))
           (throw
             (IllegalArgumentException.
               (str "Don't know how to convert " (class x) " into " (g/pprint-type dst)))))

         (= 'seq wrapper)
         (if-let [f (seq-converter dst)]
           (f x (if source-type (dissoc options :source-type) options))
           x)

         (= 'stream wrapper)
         (if-let [f (stream-converter dst)]
           (f x (if source-type (dissoc options :source-type) options))
           x)

         :else
         (throw (IllegalArgumentException. (str "invalid wrapper type: " (pr-str wrapper) " " (pr-str (.type src)))))))))

(defn possible-conversions
  "Returns a list of all possible conversion targets from value."
  [src]
  (let [^Type src (g/type src)
        pred (cond
               (.type src)
               (partial converter src)

               (= 'seq (.wrapper src))
               seq-converter

               (= 'stream (.wrapper src))
               stream-converter)]
    (->> @conversions
      g/possible-targets
      (filter pred)
      (map g/pprint-type))))

(let [memoized-cost (fast-memoize
                      (fn [src dst]
                        (if-let [path (g/conversion-path @conversions src dst)]
                          (:cost path)
                          9999)))]
  (defn conversion-cost
    "Returns the estimated cost of converting the data `x` to the destination type `dst`."
    ^long [x dst]
    (memoized-cost (type-descriptor x) (normalize-type-descriptor dst))))

;;; transfer

(defn- default-transfer
  [source sink {:keys [chunk-size] :or {chunk-size 1024} :as options}]
  (loop []
    (when-let [b (proto/take-bytes! source chunk-size options)]
      (proto/send-bytes! sink b options)
      (recur))))

(def ^:private transfer-fn
  (fast-memoize
    (fn this [^Type src ^Type dst]
      (let [converter-fn (cond
                           (nil? (.wrapper src))
                           converter

                           (#{'seq 'vector} (.wrapper src))
                           (fn [_ d] (seq-converter d))

                           (= 'stream (.wrapper src))
                           (fn [_ d] (stream-converter d)))]

        ;; TODO: do a reverse traversal, not an exhaustive forward search
        (let [[src' dst'] (->> @src->dst->transfer
                            keys
                            (map (fn [src']
                                   (and
                                     (converter-fn src src')
                                     (when-let [dst' (some
                                                       #(and (converter-fn dst %) %)
                                                       (keys (@src->dst->transfer src')))]
                                       [src' dst']))))
                            (remove nil?)
                            first)]
          (cond

            (and src' dst')
            (let [f (get-in @src->dst->transfer [src' dst'])]
              (fn [source sink options]
                (let [source' (convert source src' options)
                      sink' (convert sink dst' options)]
                  (f source' sink' options))))

            (and
              (converter-fn src (g/type #'proto/ByteSource))
              (converter dst (g/type #'proto/ByteSink)))
            (fn [source sink {:keys [close?] :or {close? true} :as options}]
              (let [source' (convert source #'proto/ByteSource options)
                    sink' (convert sink #'proto/ByteSink options)]
                (default-transfer source' sink' options)
                (when close?
                  (doseq [x [source sink source' sink']]
                    (when (proto/closeable? x)
                      (proto/close x))))))

            :else
            nil))))))

;; for byte transfers
(defn transfer
  "Transfers, if possible, all bytes from `source` into `sink`.  If this cannot be accomplished, an IllegalArgumentException is
   thrown.

   `options` is a map whose available settings depends on the source and sink types:

   `chunk-size` - if a stream is being transformed into a sequence of discrete chunks, `:chunk-size` describes the
                  size of the chunks, which default to 4096 bytes.

   `encoding`   - if a string is being encoded or decoded, `:encoding` describes the charset that is used, which
                  defaults to 'UTF-8'

   `append?`    - if a file is being written to, `:append?` determines whether the bytes will overwrite the existing content
                  or be appended to the end of the file.  This defaults to true.

   `close?`     - whether the sink should be closed once the transfer is done, defaults to true."
  ([source sink]
     (transfer source sink nil))
  ([source sink options]
     (transfer source nil sink options))
  ([source source-type sink options]
     (let [src (type-descriptor source)
           dst (type-descriptor sink)]
       (if-let [f (transfer-fn src dst)]
         (f source sink options)
         (throw (IllegalArgumentException. (str "Don't know how to transfer between " (g/pprint-type src) " to " (g/pprint-type dst))))))))

(def ^{:doc "Web-scale."} dev-null
  (reify proto/ByteSink
    (send-bytes! [_ _ _])))

(defn optimized-transfer?
  "Returns true if an optimized transfer function exists for the given source and sink objects."
  [type-descriptor sink-type]
  (boolean (transfer-fn type-descriptor sink-type)))

;;; conversion definitions

(def-conversion ^{:cost 0} [(stream-of bytes) InputStream]
  [s options]
  (let [ps (ps/pushback-stream (get options :buffer-size 1024))]
    (d/loop []
      (d/chain (s/take! s ::none)
        (fn [^bytes msg]
          (if (identical? ::none msg)
            (do
              (ps/close ps)
              false)
            (ps/put-array ps msg 0 (alength msg))))
        (fn [result]
          (when result
            (d/recur)))))
    (ps/->input-stream ps)))

(def-conversion ^{:cost 0} [(stream-of ByteBuffer) InputStream]
  [s options]
  (let [ps (ps/pushback-stream (get options :buffer-size 1024))]
    (d/loop []
      (d/chain (s/take! s ::none)
        (fn [^ByteBuffer msg]
          (if (identical? ::none msg)
            (do
              (ps/close ps)
              false)
            (ps/put-buffer ps (.duplicate msg))))
        (fn [result]
          (when result
            (d/recur)))))
    (ps/->input-stream ps)))

;; byte-array => byte-buffer
(def-conversion ^{:cost 0} [bytes ByteBuffer]
  [ary {:keys [direct?] :or {direct? false}}]
  (if direct?
    (let [len (Array/getLength ary)
          ^ByteBuffer buf (ByteBuffer/allocateDirect len)]
      (.put buf ary 0 len)
      (.position buf 0)
      buf)
    (ByteBuffer/wrap ary)))

;; byte-array => input-stream
(def-conversion ^{:cost 0} [bytes InputStream]
  [ary]
  (ByteArrayInputStream. ary))

;; byte-buffer => input-stream
(def-conversion ^{:cost 0} [ByteBuffer InputStream]
  [buf]
  (ByteBufferInputStream. (.duplicate buf)))

;; byte-buffer => byte-array
(def-conversion [ByteBuffer bytes]
  [buf]
  (if (.hasArray buf)
    (if (== (alength (.array buf)) (.remaining buf))
      (.array buf)
      (let [ary (Utils/byteArray (.remaining buf))]
        (doto buf
          .mark
          (.get ary 0 (.remaining buf))
          .reset)
        ary))
    (let [^bytes ary (Utils/byteArray (.remaining buf))]
      (doto buf .mark (.get ary) .reset)
      ary)))

;; sequence of byte-buffers => byte-buffer
(def-conversion [(vector-of ByteBuffer) ByteBuffer]
  [bufs {:keys [direct?] :or {direct? false}}]
  (cond
    (empty? bufs)
    (ByteBuffer/allocate 0)

    (and (empty? (rest bufs)) (not (proto/closeable? bufs)))
    (first bufs)

    :else
    (let [len (reduce + (map #(.remaining ^ByteBuffer %) bufs))
          buf (if direct?
                (ByteBuffer/allocateDirect len)
                (ByteBuffer/allocate len))]
      (doseq [^ByteBuffer b bufs]
        (.mark b)
        (.put buf b)
        (.reset b))
      (when (proto/closeable? bufs)
        (proto/close bufs))
      (.flip buf))))

;; byte-buffer => sequence of byte-buffers
(def-conversion ^{:cost 0} [ByteBuffer (vector-of ByteBuffer)]
  [buf {:keys [chunk-size]}]
  (if chunk-size
    (let [lim (.limit buf)
          indices (range (.position buf) lim chunk-size)]
      (mapv
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
  (lazy-seq
    (when-let [b (proto/take-bytes! channel chunk-size options)]
      (cons b (convert channel (seq-of ByteBuffer) options)))))

;; input-stream => channel
(def-conversion ^{:cost 0} [InputStream ReadableByteChannel]
  [input-stream]
  (Channels/newChannel input-stream))

;; string => byte-array
(def-conversion ^{:cost 2} [String byte-array]
  [s {:keys [encoding] :or {encoding "UTF-8"}}]
  (.getBytes s ^String (name encoding)))

;; byte-array => string
(def-conversion ^{:cost 2} [bytes String]
  [ary {:keys [encoding] :or {encoding "UTF-8"}}]
  (String. ^bytes ary (name encoding)))

;; lazy-seq of byte-buffers => channel
(def-conversion ^{:cost 1.5} [(seq-of ByteBuffer) ReadableByteChannel]
  [bufs]
  (let [pipe (Pipe/open)
        ^WritableByteChannel sink (.sink pipe)
        source (doto ^AbstractSelectableChannel (.source pipe)
                 (.configureBlocking true))]
    (future
      (try
        (loop [s bufs]
          (when (and (not (empty? s)) (.isOpen sink))
            (let [buf (.duplicate ^ByteBuffer (first s))]
              (.write sink buf)
              (recur (rest s)))))
        (finally
          (.close sink))))
    source))

(def-conversion ^{:cost 1.5} [(seq-of #'proto/ByteSource) InputStream]
  [srcs options]
  (let [chunk-size (get options :chunk-size 65536)
        out (PipedOutputStream.)
        in (PipedInputStream. out chunk-size)]
    (future
      (try
        (loop [s srcs]
          (when-not (empty? s)
            (transfer (first s) out)
            (recur (rest s))))
        (finally
          (.close out))))
    in))

(def-conversion ^{:cost 1.5} [InputStream byte-array]
  [in options]
  (let [out (ByteArrayOutputStream. (p/max 64 (.available in)))
        buf (Utils/byteArray 16384)]
    (loop []
      (let [len (.read in buf 0 16384)]
        (when-not (neg? len)
          (.write out buf 0 len)
          (recur))))
    (.toByteArray out)))

#_(let [ary (Utils/byteArray 0)]
  (def-conversion ^{:cost 0} [::nil byte-array]
    [src options]
    ary))

(def-conversion ^{:cost 2} [#'proto/ByteSource byte-array]
  [src options]
  (let [os (ByteArrayOutputStream.)]
    (transfer src os)
    (.toByteArray os)))

;; generic byte-source => lazy char-sequence
(def-conversion ^{:cost 2} [#'proto/ByteSource CharSequence]
  [source options]
  (cs/decode-byte-source
    #(when-let [bytes (proto/take-bytes! source % options)]
       (convert bytes ByteBuffer options))
    #(when (proto/closeable? source)
       (proto/close source))
    options))

;; input-stream => reader
(def-conversion ^{:cost 1.5} [InputStream Reader]
  [input-stream {:keys [encoding] :or {encoding "UTF-8"}}]
  (BufferedReader. (InputStreamReader. input-stream ^String encoding)))

;; reader => char-sequence
(def-conversion ^{:cost 1.5} [Reader CharSequence]
  [reader {:keys [chunk-size] :or {chunk-size 2048}}]
  (let [ary (char-array chunk-size)
        sb (StringBuilder.)]
    (loop []
      (let [n (.read reader ary 0 chunk-size)]
        (if (pos? n)
          (do
            (.append sb ary 0 n)
            (recur))
          (.toString sb))))))

;; char-sequence => string
(def-conversion [CharSequence String]
  [char-sequence]
  (.toString char-sequence))

(def-conversion [(vector-of String) String]
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
    (g/closeable-seq
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
        (.force fc true)
        (.close fc)))))

(def-transfer [File WritableByteChannel]
  [file
   channel
   {:keys [chunk-size
           close?]
    :or {chunk-size (int 1e6)
         close? true}
    :as options}]
  (let [^FileChannel fc (convert file ReadableByteChannel options)]
    (try
      (loop [idx 0]
        (let [n (.transferTo fc idx chunk-size channel)]
          (when (pos? n)
            (recur (+ idx n)))))
      (finally
        (when close?
          (.close ^WritableByteChannel channel))
        (.close fc)))))

(def-transfer [InputStream OutputStream]
  [input-stream
   output-stream
   {:keys [chunk-size
           close?]
    :or {chunk-size 4096
         close? true}
    :as options}]
  (let [ary (Utils/byteArray chunk-size)]
    (try
      (loop []
        (let [n (.read ^InputStream input-stream ary)]
          (when (pos? n)
            (.write ^OutputStream output-stream ary 0 n)
            (recur))))
      (.flush ^OutputStream output-stream)
      (finally
        (.close ^InputStream input-stream)
        (when close?
          (.close ^OutputStream output-stream))))))

;;; protocol extensions

(extend-protocol proto/ByteSink

  OutputStream
  (send-bytes! [this b _]
    (let [^OutputStream os this]
      (.write os ^bytes (convert b byte-array))))

  WritableByteChannel
  (send-bytes! [this b _]
    (let [^WritableByteChannel ch this]
      (.write ch ^ByteBuffer (convert b ByteBuffer)))))

(extend-protocol proto/ByteSource

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
    (let [^ByteBuffer buf (if direct?
                            (ByteBuffer/allocateDirect n)
                            (ByteBuffer/allocate n))]
      (while
        (and
          (.isOpen this)
          (pos? (.read this buf))))

      (when (pos? (.position buf))
        (.flip buf))))

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
      (let [s (convert (.duplicate buf) String {:encoding "ISO-8859-1"})
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
     (condp instance? x
       ByteBuffer x
       byte-array (ByteBuffer/wrap x)
       String (ByteBuffer/wrap (.getBytes ^String x (name (get options :encoding "UTF-8"))))
       (convert x ByteBuffer options))))

(defn to-byte-buffers
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
     (condp instance? x
       byte-array x
       String (.getBytes ^String x (name (get options :encoding "UTF-8")))
       (convert x byte-array options))))

(defn to-byte-arrays
  "Converts the object to a byte-array."
  ([x]
     (to-byte-array x nil))
  ([x options]
     (convert x (seq-of byte-array) options)))

(defn ^InputStream to-input-stream
  "Converts the object to a `java.io.InputStream`."
  ([x]
     (to-input-stream x nil))
  ([x options]
     (condp instance? x
       byte-array (ByteArrayInputStream. x)
       ByteBuffer (ByteBufferInputStream. x)
       (convert x InputStream options))))

(defn ^DataInputStream to-data-input-stream
  ([x]
     (to-data-input-stream x nil))
  ([x options]
     (if (instance? DataInputStream x)
       x
       (DataInputStream. (to-input-stream x)))))

(defn ^InputStream to-output-stream
  "Converts the object to a `java.io.OutputStream`."
  ([x]
     (to-output-stream x nil))
  ([x options]
     (convert x OutputStream options)))

(defn ^CharSequence to-char-sequence
  "Converts to the object to a `java.lang.CharSequence`."
  ([x]
     (to-char-sequence x nil))
  ([x options]
     (if (instance? CharSequence x)
       x
       (convert x CharSequence options))))

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
     (let [encoding (get options :encoding "UTF-8")]
       (condp instance? x
         String x
         byte-array (String. ^"[B" x ^String (name encoding))
         (convert x String options)))))

(defn to-reader
  "Converts the object to a java.io.Reader."
  ([x]
     (to-reader x nil))
  ([x options]
     (convert x Reader options)))

(defn to-line-seq
  "Converts the object to a lazy sequence of newline-delimited strings."
  ([x]
     (to-line-seq x nil))
  ([x options]
     (let [reader (convert x Reader options)
           reader (BufferedReader. ^Reader reader)
           line! (fn line! []
                   (lazy-seq
                     (when-let [l (try
                                    (.readLine reader)
                                    (catch IOException e
                                      nil))]
                       (cons l (line!)))))]
       (line!))))

(defn to-byte-source
  "Converts the object to something that satisfies `ByteSource`."
  ([x]
     (to-byte-source x nil))
  ([x options]
     (convert x #'proto/ByteSource options)))

(defn to-byte-sink
  "Converts the object to something that satisfies `ByteSink`."
  ([x]
     (to-byte-sink x nil))
  ([x options]
     (convert x #'proto/ByteSink options)))

;;;

(defn- cmp-bufs
  ^long [^ByteBuffer a' ^ByteBuffer b']
  (let [diff (p/- (.remaining a') (.remaining b'))
        sign (long (if (pos? diff) -1 1))
        a (if (pos? diff) b' a')
        b (if (pos? diff) a' b')
        limit (p/>> (.remaining a) 2)
        a-offset (.position a)
        b-offset (.position b)]
    (let [cmp (loop [idx 0]
                (if (p/>= idx limit)
                  0
                  (let [cmp (p/-
                              (p/int->uint (.getInt a (p/+ idx a-offset)))
                              (p/int->uint (.getInt b (p/+ idx b-offset))))]
                    (if (p/== 0 cmp)
                      (recur (p/+ idx 4))
                      (p/* sign cmp)))))]
      (if (p/== 0 (long cmp))
        (let [limit' (.remaining a)]
          (loop [idx limit]
            (if (p/>= idx limit')
              diff
              (let [cmp (p/-
                          (p/byte->ubyte (.get a (p/+ idx a-offset)))
                          (p/byte->ubyte (.get b (p/+ idx b-offset))))]
                (if (p/== 0 cmp)
                  (recur (p/inc idx))
                  (p/* sign cmp))))))
        cmp))))

(defn compare-bytes
  "Returns a comparison result for two byte streams."
  ^long [a b]
  (if (and
        (or
          (instance? byte-array a)
          (instance? ByteBuffer a)
          (instance? String a))
        (or
          (instance? byte-array b)
          (instance? ByteBuffer b)
          (instance? String b)))
    (cmp-bufs (to-byte-buffer a) (to-byte-buffer b))
    (loop [a (to-byte-buffers a), b (to-byte-buffers b)]
      (cond
        (empty? a)
        (if (empty? b) 0 -1)

        (empty? b)
        1

        :else
        (let [cmp (cmp-bufs (first a) (first b))]
          (if (p/== 0 cmp)
            (recur (rest a) (rest b))
            cmp))))))

(defn bytes=
  "Returns true if the two byte streams are equivalent."
  [a b]
  (p/== 0 (compare-bytes a b)))
