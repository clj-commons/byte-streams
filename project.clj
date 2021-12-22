(defproject org.clj-commons/byte-streams (or (System/getenv "PROJECT_VERSION") "0.2.11")
  :description "A simple way to handle the menagerie of Java byte representations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  :dependencies [[primitive-math "0.1.6"]
                 [manifold "0.1.9"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.3"]
                                  [org.clojure/test.check "1.1.0"]
                                  [rhizome "0.2.9"]
                                  [codox-md "0.2.0" :exclusions [org.clojure/clojure]]
                                  [criterium "0.4.6"]]}
             :ci {:javac-options ["-target" "1.8" "-source" "1.8"]
                  :dependencies [[org.clojure/clojure "1.10.1"]
                                 [org.clojure/test.check "1.1.0"]
                                 [rhizome "0.2.9"]]}}
  :test-selectors {:stress :stress
                   :default (complement :stress)}
  :plugins [[lein-codox "0.10.3"]
            [jonase/eastwood "0.4.3"]
            [lein-jammin "0.1.1"]
            [ztellman/lein-cljfmt "0.1.10"]]
  :cljfmt {:indents {#".*" [[:inner 0]]}}
  :codox {:source-uri "https://github.com/clj-commons/byte-streams/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}
          :namespaces [clj-commons.byte-streams]}
  :global-vars {*warn-on-reflection* true}
  :java-source-paths ["src"]
  :jvm-opts ^:replace ["-server" "-Xmx4g"])
