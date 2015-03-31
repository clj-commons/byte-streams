(ns byte-streams.utils
  (:require
    [clj-tuple :as t])
  (:import
    [java.util.concurrent
     ConcurrentHashMap]))

(defmacro memoize-form [m f & args]
  `(let [k# (t/vector ~@args)]
     (let [v# (.get ~m k#)]
       (if (nil? v#)
         (let [v# (delay (~f ~@args))]
           @(or (.putIfAbsent ~m k# v#) v#))
         @v#))))

(defn fast-memoize
  "A version of `memoize` which has equivalent behavior, but is faster."
  [f]
  (let [m (ConcurrentHashMap.)]
    (fn
      ([]
         (memoize-form m f))
      ([x]
         (memoize-form m f x))
      ([x y]
         (memoize-form m f x y))
      ([x y z]
         (memoize-form m f x y z))
      ([x y z w]
         (memoize-form m f x y z w))
      ([x y z w u]
         (memoize-form m f x y z w u))
      ([x y z w u v]
         (memoize-form m f x y z w u v))
      ([x y z w u v & rest]
         (let [k (list* x y z w u v rest)]
           (let [v (.get ^ConcurrentHashMap m k)]
             (if (nil? v)
               (let [v (delay (apply f k))]
                 @(or (.putIfAbsent m k v) v))
               @v)))))))
