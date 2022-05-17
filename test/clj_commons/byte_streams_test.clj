(ns clj-commons.byte-streams-test
  (:require
    [clj-commons.byte-streams :refer [bytes= compare-bytes conversion-path convert dev-null possible-conversions seq-of stream-of to-byte-array to-byte-buffer to-byte-buffers to-input-stream to-string transfer vector-of] :as bs]
    [clojure.test :refer :all]
    [clj-commons.byte-streams.char-sequence :as cs]
    [clojure.java.io :as io]
    [clj-commons.primitive-math :as p])
  (:refer-clojure
    :exclude [vector-of])
  (:import
    (java.io File)
    (java.nio ByteBuffer)
    (java.nio.channels WritableByteChannel ReadableByteChannel)
    (java.util Arrays)))

(def ^String text
  "The suburb of Saffron Park lay on the sunset side of London, as red and ragged as a cloud of sunset. It was built of a bright brick throughout; its sky-line was fantastic, and even its ground plan was wild. It had been the outburst of a speculative builder, faintly tinged with art, who called its architecture sometimes Elizabethan and sometimes Queen Anne, apparently under the impression that the two sovereigns were identical. It was described with some justice as an artistic colony, though it never in any definable way produced any art. But although its pretensions to be an intellectual centre were a little vague, its pretensions to be a pleasant place were quite indisputable. The stranger who looked for the first time at the quaint red houses could only think how very oddly shaped the people must be who could fit in to them. Nor when he met the people was he disappointed in this respect. The place was not only pleasant, but perfect, if once he could regard it not as a deception but rather as a dream. Even if the people were not \"artists,\" the whole was nevertheless artistic. That young man with the long, auburn hair and the impudent face—that young man was not really a poet; but surely he was a poem. That old gentleman with the wild, white beard and the wild, white hat—that venerable humbug was not really a philosopher; but at least he was the cause of philosophy in others. That scientific gentleman with the bald, egg-like head and the bare, bird-like neck had no real right to the airs of science that he assumed. He had not discovered anything new in biology; but what biological creature could he have discovered more singular than himself? Thus, and thus only, the whole place had properly to be regarded; it had to be considered not so much as a workshop for artists, but as a frail but finished work of art. A man who stepped into its social atmosphere felt as if he had stepped into a written comedy.")

(defn eval' [x]
  (if (sequential? x)
    (condp = (first x)
      'vector-of (vector-of (second x))
      'stream-of (stream-of (second x))
      'seq-of (seq-of (second x)))
    x))

(def ary
  (byte-array (map byte (range -127 127))))

(deftest test-roundtrips
  (let [pairwise-conversions (->> String
                               possible-conversions
                               (mapcat #(map list (repeat %) (possible-conversions %)))
                               distinct
                               (map (partial map eval')))]
    (doseq [[src dst] pairwise-conversions]

      (is (= text
            (-> text
              (convert src)
              (convert dst)
              (convert String))
            (-> text
              (convert src)
              (convert dst {:source-type src})
              (convert String {:source-type dst})))
        (str (pr-str src) " -> " (pr-str dst)))))

  ;; make sure none of our intermediate representations are strings if our target isn't a string
  (let [invalid-destinations (->> #{String CharSequence java.io.Reader}
                               (mapcat #(vector % (list 'seq-of %) (list 'stream-of %)))
                               set)
        pairwise-conversions (->> (class ary)
                               possible-conversions
                               (remove invalid-destinations)
                               (mapcat #(map list (repeat %) (remove invalid-destinations (possible-conversions %))))
                               distinct
                               (map (partial map eval')))]
    (doseq [[src dst] pairwise-conversions]

      (is (= (seq ary)
            (-> ary
              (convert src)
              (convert dst)
              (convert (class ary))
              seq)
            (-> ary
              (convert src)
              (convert dst {:source-type src})
              (convert (class ary) {:source-type dst})
              seq))
        (str src " -> " dst ": "
          (pr-str
            (concat
              (conversion-path (class ary) src)
              (conversion-path src dst)
              (conversion-path dst (class ary)))))))))

(defn temp-file []
  (doto (File/createTempFile "byte-streams" ".tmp")
    (.deleteOnExit)))

(deftest test-transfer
  (doseq [dst (->> String
                possible-conversions
                (map eval'))]

    (let [file (temp-file)
          file' (temp-file)]
      (transfer (convert text dst) dev-null)
      (transfer (convert text dst) file {:chunk-size 128})
      (is (= text (to-string file)))
      (transfer (convert text dst) file {:chunk-size 128, :append? false})
      (is (= text (to-string file)))
      (is (= text (to-string (to-byte-buffers file {:chunk-size 128}))))

      (transfer file file')
      (is (= text (to-string file')))
      (is (= text (to-string (to-byte-buffers file' {:chunk-size 128})))))))

;;;

(deftest test-byte-buffer
  (let [arr (.getBytes ^String text)
        pos 13
        buf (doto (ByteBuffer/wrap arr) (.position pos))]
    (to-byte-array buf)
    (to-byte-array (repeat 2 buf))
    (is (= pos (.position buf)))))

(deftest test-seq-of-byte-buffer
  (let [buf (doto ^ByteBuffer (to-byte-buffer "quick brown fox")
              (.position 3)
              (.limit 6))
        arr (to-byte-array buf)]
    (doseq [chunk-size (range 1 (+ 1 (.capacity buf)))]
      (is (Arrays/equals
            (to-byte-array (convert buf (seq-of ByteBuffer) {:chunk-size chunk-size}))
            arr)))))

(deftest ^:stress test-large-chunked-stream
  (let [text-seq (repeat 1e4 text)]
    (is (bytes=
          (to-byte-array text-seq)
          (-> text-seq
            to-input-stream
            (convert (seq-of ByteBuffer) {:chunk-size 1})
            to-byte-array)))))

(deftest test-unicode-decoding
  (let [three-byte-char "丁"
        text (apply str (repeat 1e4 three-byte-char))
        text-bytes (to-byte-array text)]
    (is (bytes= text-bytes (-> text-bytes to-string to-byte-array)))
    (is (bytes= text-bytes (-> text-bytes to-input-stream to-string to-byte-array)))
    (is (bytes= text-bytes (-> text-bytes (to-input-stream {:chunk-size 128}) to-string to-byte-array)))))

(deftest compare-bytes-former-bug
  (let [bx (convert (byte-array [0x00 0x00 0x00 0x01]) java.nio.ByteBuffer)
        by (convert (byte-array [0x80 0x00 0x00 0x01]) java.nio.ByteBuffer)]
    (is (= [bx by] (sort compare-bytes [bx by])))
    (is (= [bx by] (sort compare-bytes [by bx])))))

(defn- write-zeros-file
  "Write out a file of nothing but zeros"
  [size]
  (let [f (doto (File/createTempFile "byte-streams-test-" nil)
                (.deleteOnExit))
        buf-size 64
        zs (byte-array buf-size (byte 0))
        num-bufs (int (quot size buf-size))
        remainder (int (mod size buf-size))]
    (with-open [os (io/output-stream f)]
      (loop [cnt num-bufs]
        (when (p/> cnt 0)
          (.write os zs)
          (recur (p/dec cnt))))
      (when (pos? remainder)
        (.write os (byte-array remainder (byte 0)))))
    f))

(defn- bb-stream-size
  [s]
  (reduce
    (fn [sz ^ByteBuffer bb] (p/+ ^int sz (.limit bb)))
    (int 0)
    s))

(deftest large-file
  (testing "can read whole file"
    (let [size (int 1e8)
          chunk-size (p// size 10)
          f (write-zeros-file size)]
      (testing "from streams"
        (testing "all at once"
          (with-open [in (clojure.java.io/input-stream f)]
            (is (== (bb-stream-size (convert in (seq-of ByteBuffer)))
                    size))))
        (testing "in chunks"
          (with-open [in (clojure.java.io/input-stream f)]
            (is (== (bb-stream-size (convert in
                                             (seq-of ByteBuffer)
                                             {:chunk-size chunk-size}))
                    size)))))

      (testing "from java.io.File"
        (is (== (bb-stream-size (convert f (seq-of ByteBuffer)))
                size))

        (testing "partial read, then close"
          (let [n 4
                s (convert f (seq-of ByteBuffer) {:chunk-size chunk-size})
                _ (nth s n)]
            (.close s)
            (is (some? (nth s n)))
            (is (thrown? Exception
                         (nth s (inc n))))))))))

(deftest from-io-file
  (testing "File conversions not tested elsewhere"
    (testing "read/write to same spot"
      (let [size 1
            val (byte (rand-int 127))
            val-bb (ByteBuffer/wrap (byte-array 1 val))
            f (write-zeros-file size)]
        (with-open [^WritableByteChannel wbc (convert f WritableByteChannel {:append? false})]
          (.write wbc val-bb))
        (with-open [^ReadableByteChannel rbc (convert f ReadableByteChannel)]
          (let [bb (ByteBuffer/allocate 1)]
            (.read rbc bb)
            (is (bytes= val-bb bb))))))

    (testing "append"
      (let [^int size (rand-int 100)
            val (byte (rand-int 127))
            val-bb (ByteBuffer/wrap (byte-array 1 val))
            f (write-zeros-file size)]
        (with-open [^WritableByteChannel wbc (convert f WritableByteChannel {:append? true})]
          (.write wbc val-bb))
        (with-open [^ReadableByteChannel rbc (convert f ReadableByteChannel)]
          (let [bb (ByteBuffer/allocate (inc size))]
            (.read rbc bb)

            (let [bb-array (.array bb)]
              (dotimes [i size]
                (is (= (aget bb-array i) 0)))
              (is (= (aget bb-array size) val)))))))))



