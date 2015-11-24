(ns byte-streams.graph
  (:refer-clojure :exclude [vector type byte-array])
  (:require
    [clj-tuple :refer [vector]]
    [manifold.stream :as s]
    [byte-streams
     [utils :as u]
     [protocols :as p]])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]
    [java.util
     LinkedList
     PriorityQueue]))

(def byte-array (class (clojure.core/byte-array 0)))

(declare pprint-type)

(deftype Conversion [f ^double cost]
  Object
  (equals [_ x]
    (and
      (instance? Conversion x)
      (identical? f (.f ^Conversion x))
      (== cost (.cost ^Conversion x))))
  (hashCode [_]
    (bit-xor (System/identityHashCode f) (unchecked-int cost))))

(deftype Type [wrapper type]
  Object
  (equals [_ x]
    (and
      (instance? Type x)
      (= wrapper (.wrapper ^Type x))
      (= type (.type ^Type x))))
  (hashCode [_]
    (bit-xor
      (hash wrapper)
      (hash type)))
  (toString [this]
    (pr-str (pprint-type this))))

(defn pprint-type [^Type x]
  (if-let [wrapper (.wrapper x)]
    (list (symbol (str wrapper "-of")) (.type x))
    (.type x)))

(defn type
  ([t]
     (if (instance? Type t)
       t
       (type nil t)))
  ([wrapper t]
     (Type. wrapper
       (if (var? t)
         @t
         t))))

(defn- protocol? [x]
  (and (map? x) (contains? x :on-interface)))

(defn canonicalize [x]
  (if (protocol? x)
    @(:var x)
    x))

(defn- class-satisfies? [protocol ^Class c]
  (boolean
    (or
      (.isAssignableFrom ^Class (:on-interface protocol) c)
      (some
        #(.isAssignableFrom ^Class % c)
        (keys (:impls protocol))))))

(defn assignable? [^Type a ^Type b]
  (and
    (= (.wrapper a) (.wrapper b))
    (let [a (canonicalize (.type a))
          b (canonicalize (.type b))]
      (cond
        (and (class? a) (class? b))
        (.isAssignableFrom ^Class b a)

        (and (protocol? b) (class? a))
        (class-satisfies? b a)

        :else
        (= a b)))))

(defprotocol IConversionGraph
  (assoc-conversion [_ src dst f cost])
  (equivalent-targets [_ dst])
  (possible-sources [_])
  (possible-targets [_])
  (possible-conversions [_ src])
  (conversion [_ src dst]))

(defn implicit-conversions [^Type src]
  (cond

    ;; vector -> seq
    (= 'vector (.wrapper src))
    [[[src (Type. 'seq (.type src))] (Conversion. (fn [x _] (seq x)) 1)]]

    ;; seq -> stream
    (= 'seq (.wrapper src))
    [[[src (Type. 'stream (.type src))] (Conversion. (fn [x _] (s/->source x)) 1)]]

    ;; stream -> seq
    (= 'stream (.wrapper src))
    [[[src (Type. 'seq (.type src))] (Conversion. (fn [x _] (s/stream->seq x)) 1)]]

    :else
    nil))

(deftype ConversionGraph [m]
  IConversionGraph
  (assoc-conversion [_ src dst f cost]
    (let [m' (assoc-in m [src dst] (Conversion. f cost))
          m' (if (and
                   (nil? (.wrapper ^Type src))
                   (nil? (.wrapper ^Type dst)))
               (let [src (.type ^Type src)
                     dst (.type ^Type dst)]
                 (-> m'
                   (assoc-in [(Type. 'seq src) (Type. 'seq dst)]
                     (Conversion. (fn [x options] (map #(f % options) x)) cost))
                   (assoc-in [(Type. 'stream src) (Type. 'stream dst)]
                     (Conversion. (fn [x options] (s/map #(f % options) x)) (+ cost 0.1)))))
               m')]
      (ConversionGraph. m')))
  (possible-sources [_]
    (keys m))
  (possible-targets [_]
    (->> m vals (mapcat keys)))
  (equivalent-targets [_ dst]
    (->> m
      vals
      (mapcat keys)
      (filter #(assignable? % dst))))
  (possible-conversions [_ src]
    (->> m
      keys
      (filter (partial assignable? src))
      (mapcat (fn [src]
                (map
                  (fn [[k v]]
                    [[src k] v])
                  (get m src))))
      (concat (implicit-conversions src))
      (into {}))))

(defn conversion-graph []
  (ConversionGraph. {}))

;;;

(defrecord ConversionPath [path fns visited? cost]
  Comparable
  (compareTo [_ x]
    (let [cmp (compare cost (.cost ^ConversionPath x))]
      (if (zero? cmp)
        (compare (count path) (count (.path ^ConversionPath x)))
        cmp))))

(defn- conj-path [^ConversionPath p src dst ^Conversion c]
  (ConversionPath.
    (conj (.path p) [src dst])
    (conj (.fns p) (.f c))
    (conj (.visited? p) dst)
    (+ (.cost p) (.cost c))))

(def conversion-path
  (u/fast-memoize
    (fn [g src dst]
      (let [path (ConversionPath. [] [] #{src} 0)]
        (if (assignable? src dst)
          path
          (let [q (doto (PriorityQueue.) (.add path))
                dsts (equivalent-targets g dst)]
            (loop []
              (when-let [^ConversionPath p (.poll q)]
                (let [curr (or (-> p .path last second) src)]
                  (if (some #(assignable? curr %) dsts)
                    p
                    (do
                      (doseq [[[src dst] c] (->> curr
                                              (possible-conversions g)
                                              (remove (fn [[[src dst] c]] ((.visited? p) dst))))]
                        (.add q (conj-path p src dst c)))
                      (recur))))))))))))

;;;

(defn closeable-seq [s exhaustible? close-fn]
  (if (empty? s)
    (when exhaustible?
      (close-fn)
      nil)
    (reify

      clojure.lang.IPending
      (isRealized [_]
        (or
          (not (instance? clojure.lang.IPending s))
          (realized? s)))

      Object
      (finalize [_]
        (close-fn))

      java.io.Closeable
      (close [_]
        (close-fn))

      clojure.lang.Sequential
      clojure.lang.ISeq
      clojure.lang.Seqable
      (seq [this] this)
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
      (equiv [a b]
        (= s b)))))

(defn conversion-fn [g src dst]
  (when-let [path (conversion-path g src dst)]
    (condp = (count (:path path))
      0 (fn [x _] x)

      1 (let [f (->> path :fns first)]
          (if (p/closeable? src)
            (fn [x options]
              (let [x' (f x options)]
                (when-not (p/closeable? x')
                  (p/close x))
                x'))
            f))

      ;; multiple stages
      (let [fns (->> path :fns (apply vector))]
        (fn [x options]
          (let [close-fns (LinkedList.)
                result (reduce
                         (fn [x f]

                           ;; keep track of everything that needs to be closed once the bytes are exhausted
                           (when (p/closeable? x)
                             (.add close-fns #(p/close x)))
                           (f x options))
                         x
                         fns)]
            (if-let [close-fn (when-not (or (p/closeable? result)
                                          (.isEmpty close-fns))
                                #(loop []
                                   (when-let [f (.poll close-fns)]
                                     (f)
                                     (recur))))]
              (cond

                (seq? result)
                (closeable-seq result true close-fn)

                (s/source? result)
                (do
                  (s/on-drained result close-fn)
                  result)

                :else
                (do
                  ;; we assume that if the end-result is closeable, it will take care of all the intermediate
                  ;; objects beneath it.  I think this is true as long as we're not doing multiple streaming
                  ;; reads, but this might need to be revisited.
                  (when-not (p/closeable? result)
                    (close-fn))
                  result))
              result)))))))

(defn seq-conversion-fn [g convert wrapper dst]
  (let [path (->> g
               possible-sources
               (remove #(nil? (.wrapper ^Type %)))
               (map #(conversion-path g % dst))
               (remove nil?)
               (sort-by :cost)
               first)
        ^Type src (-> path :path first first)]

    (when src
      (let [wrapper' (.wrapper src)
            type' (.type src)]
        (fn [x options]
          (->> x

            ((condp = [wrapper wrapper']
               '[seq vector] vec
               '[stream vector] (comp vec s/stream->seq)
               '[seq stream] s/->source
               '[stream seq] s/stream->seq
               identity))

            ((condp = wrapper'
               'vector (partial mapv #(convert % type' options))
               'seq (partial map #(convert % type' options))
               'stream (partial s/map #(convert % type' options))))

            (#((conversion-fn g src (-> path :path last last)) % options))))))))
