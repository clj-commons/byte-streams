#!/usr/bin/env ./bb

(require '[clojure.java.shell :refer [sh]])
(require '[clojure.edn :as edn])
(require '[clojure.string :as str])

(defn make-version! []
  (let [v (edn/read-string (slurp "version.edn"))
        commit-count (str/trim-newline (:out (sh "git" "rev-list" "--count" "--first-parent" "HEAD")))]
    (str v "." commit-count)))

(if (->> (System/getenv "CIRCLE_SHA1")
         (sh "git" "show" "-s")
         :out
         (re-find #"\[ci deploy\]"))
  (do
    (println "executing " (first *command-line-args*))
    (apply sh (into (vec *command-line-args*) [:env (into {"PROJECT_VERSION" (make-version!)}
                                                          (System/getenv))])))
  (println "skipping" (first *command-line-args*)))
