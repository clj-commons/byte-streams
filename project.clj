(defproject byte-streams "0.2.2"
  :description "A simple way to handle the menagerie of Java byte represenations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[primitive-math "0.1.5"]
                 [clj-tuple "0.2.2"]
                 [manifold "0.1.6"]]
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]
                       :dependencies [[org.clojure/clojure "1.8.0"]]}
             :dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [org.clojure/test.check "0.9.0"]
                                  [codox-md "0.2.0" :exclusions [org.clojure/clojure]]]}}
  :test-selectors {:stress :stress
                   :default (complement :stress)}
  :plugins [[lein-codox "0.9.4"]
            [lein-jammin "0.1.1"]
            [ztellman/lein-cljfmt "0.1.10"]]
  :cljfmt {:indents {#".*" [[:inner 0]]}}
  :codox {:source-uri "https://github.com/ztellman/byte-streams/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}
          :namespaces [byte-streams]}
  :global-vars {*warn-on-reflection* true}
  :java-source-paths ["src"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :jvm-opts ^:replace ["-server" "-Xmx4g"])
