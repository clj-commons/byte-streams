(defproject byte-streams "0.2.0"
  :description "A simple way to handle the menagerie of Java byte represenations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[primitive-math "0.1.4"]
                 [clj-tuple "0.2.1"]
                 [manifold "0.1.0"]
                 #_[com.googlecode.juniversalchardet/juniversalchardet "1.0.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0-alpha6"]
                                  [org.clojure/test.check "0.7.0"]
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
