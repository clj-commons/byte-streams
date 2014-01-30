(ns byte-streams-test
  (:require
    [clojure.test :refer :all]
    [byte-streams :refer :all])
  (:import
    [java.io
     File]
    [java.nio
     ByteBuffer]
    [java.util
     Arrays]))

(def text
  "The suburb of Saffron Park lay on the sunset side of London, as red and ragged as a cloud of sunset. It was built of a bright brick throughout; its sky-line was fantastic, and even its ground plan was wild. It had been the outburst of a speculative builder, faintly tinged with art, who called its architecture sometimes Elizabethan and sometimes Queen Anne, apparently under the impression that the two sovereigns were identical. It was described with some justice as an artistic colony, though it never in any definable way produced any art. But although its pretensions to be an intellectual centre were a little vague, its pretensions to be a pleasant place were quite indisputable. The stranger who looked for the first time at the quaint red houses could only think how very oddly shaped the people must be who could fit in to them. Nor when he met the people was he disappointed in this respect. The place was not only pleasant, but perfect, if once he could regard it not as a deception but rather as a dream. Even if the people were not \"artists,\" the whole was nevertheless artistic. That young man with the long, auburn hair and the impudent face—that young man was not really a poet; but surely he was a poem. That old gentleman with the wild, white beard and the wild, white hat—that venerable humbug was not really a philosopher; but at least he was the cause of philosophy in others. That scientific gentleman with the bald, egg-like head and the bare, bird-like neck had no real right to the airs of science that he assumed. He had not discovered anything new in biology; but what biological creature could he have discovered more singular than himself? Thus, and thus only, the whole place had properly to be regarded; it had to be considered not so much as a workshop for artists, but as a frail but finished work of art. A man who stepped into its social atmosphere felt as if he had stepped into a written comedy.")

(def ary
  (byte-array (map byte (range -127 127))))

(defn find-missing-roundtrips []
  (remove nil?
    (for [[src dst] (->> (keys @src->dst->conversion)
                      (mapcat #(map list (repeat %) (possible-conversions %)))
                      distinct)]
      (when-not (and (conversion-path src dst) (conversion-path dst src))
        [src dst]))))

(deftest test-roundtrips
  (let [pairwise-conversions (->> text
                               possible-conversions
                               (mapcat #(map list (repeat %) (possible-conversions %)))
                               distinct)]
    (doseq [[src dst] pairwise-conversions]
      (is (= text (-> text (convert src) (convert dst) (convert String)))
        (str src " -> " dst))))

  ;; make sure none of our intermediate representations are strings if our target isn't a string
  (let [invalid-destinations #{String (seq-of String) CharSequence (seq-of CharSequence) java.io.Reader (seq-of java.io.Reader)}
        pairwise-conversions (->> ary
                               possible-conversions
                               (remove invalid-destinations)
                               (mapcat #(map list (repeat %) (remove invalid-destinations (possible-conversions %))))
                               distinct)]
    (doseq [[src dst] pairwise-conversions]
      (is (= (seq ary) (-> ary (convert src) (convert dst) to-byte-array seq))
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
  (doseq [dst (possible-conversions text)]
    (let [file (temp-file)]
      (transfer (convert text dst) dev-null)
      (transfer (convert text dst) file {:chunk-size 128})
      (is (= text (to-string file)))
      (is (= text (to-string (to-byte-buffers file {:chunk-size 128})))))))

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
            arr)))
    (is (empty? (convert buf (seq-of ByteBuffer) {:chunk-size 0})))))

(deftest ^:stress test-large-chunked-stream
  (let [text-seq (repeat 1e4 text)]
    (is (bytes=
          (to-byte-array text-seq)
          (-> text-seq
            to-input-stream
            (convert (seq-of ByteBuffer) {:chunk-size 1})
            to-byte-array)))))
