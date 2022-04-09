(ns clj-commons.byte-streams.utils)

(defmacro defprotocol+ [name & body]
  (when-not (resolve name)
    `(defprotocol ~name ~@body)))

(defmacro deftype+ [name & body]
  (when-not (resolve name)
    `(deftype ~name ~@body)))

(defmacro defrecord+ [name & body]
  (when-not (resolve name)
    `(defrecord ~name ~@body)))

(defmacro definterface+ [name & body]
  (when-not (resolve name)
    `(definterface ~name ~@body)))

(defmacro doit
  "A version of doseq that doesn't emit all that inline-destroying chunked-seq code."
  [[x it] & body]
  (let [it-sym (gensym "iterable")]
    `(let [~it-sym ~it
           it# (.iterator ~(with-meta it-sym {:tag 'java.lang.Iterable}))]
       (loop []
         (when (.hasNext it#)
           (let [~x (.next it#)]
            ~@body)
           (recur))))))

