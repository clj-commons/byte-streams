Java has a lot of different ways to represent a stream of bytes.  Depending on the author and age of a library, it might use byte-arrays, `InputStream`, `ByteBuffer`, or `Channel`.  If the bytes represent strings, there's also `String`, `Reader`, and `CharSequence` to worry about.  Remembering how to convert between all of them is a thankless task, made that much worse by libraries which define their own custom representations, or composing them with Clojure's lazy sequences.

This library is a Rosetta stone for all the byte representations Java has to offer, and gives you the freedom to forget all the APIs you never wanted to know in the first place.

### usage

```clj
[byte-streams "0.1.0-SNAPSHOT"]
```

### converting types

To convert one byte representation to another, use `byte-streams/convert`:

```clj
byte-streams> (convert "abcd" java.nio.ByteBuffer)
#<HeapByteBuffer java.nio.HeapByteBuffer[pos=0 lim=4 cap=4]>
byte-streams> (convert *1 String)
"abcd"
```

`(convert data to-type options?)` converts, if possible, the data from its current type to the destination type.  This destination type can either be a Java class, or a Clojure protocol.  However, since there's no direct route from a string to a byte-buffer, under the covers `convert` is doing whatever it takes to get the desired type:

```clj
byte-streams> (conversion-path String java.nio.ByteBuffer)
(java.lang.String [B java.nio.ByteBuffer)
```

While we can't turn a string into a `ByteBuffer`, we can turn a string into a byte-array, and a byte-array into a `ByteBuffer`.  When invoked, `convert` will choose the minimal path along the graph of available conversions.  Common conversions are exposed via `to-byte-buffer`, `to-byte-array`, `to-input-stream`, `to-readable-channel`, and `to-line-seq`.  

Every type can exist either by itself, or as a sequence.  For instance, we can create an `InputStream` representing an infinite number of hellos:

```clj
byte-stream> (to-input-stream (repeat "hello"))
#<ChannelInputStream sun.nio.ch.ChannelInputStream@5440bbb4>
```

And then we can turn that into a sequence of `ByteBuffers`:

```clj
byte-streams> (take 2 
                (convert *1 
                  (many java.nio.ByteBuffer) 
                  {:chunk-size 128}))
(#<HeapByteBuffer java.nio.HeapByteBuffer[pos=0 lim=128 cap=128]> 
 #<HeapByteBuffer java.nio.HeapByteBuffer[pos=0 lim=128 cap=128]>)
```

Notice that we describe a sequence of a type as `(many type)`, and that we've passed a map to `convert` describing the size of the `ByteBuffers` we want to create.  The two options that are currently supported are `:chunk-size`, which is relevant whenever we're converting a stream into a sequence of discrete chunks, and `:encoding`, which is relevant whenever we're encoding or decoding a string.

### custom conversions

While there are conversions defined for all common byte types, this can be extended to other libraries via `byte-streams/def-conversion`:

```clj
(def-conversion [ByteBuffer MyByteRepresentation] 
  [buf options]
  (buffer->my-representation buf options))

(convert "abc" MyByteRepresentation)
```

This conversion transitively extends to all types that can be converted to a `ByteBuffer` (i.e. all of them).  This mechanism can even be used for types unrelated to byte streams, if you're feeling adventurous.

### license

Copyright © 2013 Zachary Tellman

Distributed under the [MIT License](http://opensource.org/licenses/MIT)
