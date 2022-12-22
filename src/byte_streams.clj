(ns
  ^{:deprecated true
    :doc "DEPRECATED: moved to clj-commons.byte-streams"
    :no-doc true
    :superseded-by "clj-commons.byte-streams"}
  byte-streams
  (:refer-clojure :exclude [byte-array vector-of])
  (:require
    [clj-commons.byte-streams]
    [potemkin.namespaces])
  (:import (java.nio.file StandardOpenOption)))

;; don't use single-segment potemkin ns to avoid further single-segment issues
(potemkin.namespaces/import-vars
  [clj-commons.byte-streams

   conversions
   inverse-conversions
   src->dst->transfer
   byte-array-type
   seq-of
   stream-of
   vector-of
   type-descriptor
   normalize-type-descriptor
   tag-metadata-for
   def-conversion
   def-transfer
   converter
   seq-converter
   stream-converter
   conversion-path
   convert
   possible-conversions
   conversion-cost
   default-transfer
   transfer-fn
   transfer
   dev-null
   optimized-transfer?
   readable-character?
   print-bytes
   to-byte-buffer
   to-byte-buffers
   to-byte-array
   to-byte-arrays
   to-input-stream
   to-data-input-stream
   to-output-stream
   to-char-sequence
   to-readable-channel
   to-string
   to-reader
   to-line-seq
   to-byte-source
   to-byte-sink
   cmp-bufs
   compare-bytes
   bytes=])
