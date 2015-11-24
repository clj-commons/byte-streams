(defproject byte-streams "0.2.1-alpha2"
  :description "A simple way to handle the menagerie of Java byte represenations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[primitive-math "0.1.4"]
                 [clj-tuple "0.2.2"]
                 [manifold "0.1.1"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0"]
                                  [org.clojure/test.check "0.8.1"]
                                  [codox-md "0.2.0" :exclusions [org.clojure/clojure]]]}}
  :test-selectors {:stress :stress
                   :default (complement :stress)}
  :plugins [[codox "0.6.4"]]
  :codox {:writer codox-md.writer/write-docs
          :include [byte-streams]}
  :global-vars {*warn-on-reflection* true}
  :java-source-paths ["src"]
  :javac-options ["-target" "1.6" "-source" "1.6"]
  :jvm-opts ^:replace ["-server" "-Xmx4g"])
