(defproject clj-commons/byte-streams (or (System/getenv "version") "0.2.5-alpha")
  :description "A simple way to handle the menagerie of Java byte represenations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  :dependencies [[primitive-math "0.1.6"]
                 [clj-tuple "0.2.2"]
                 [manifold "0.1.8"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]
                                  [org.clojure/test.check "1.1.0"]
                                  [rhizome "0.2.9"]
                                  [codox-md "0.2.0" :exclusions [org.clojure/clojure]]]}
             :ci {:javac-options ["-target" "1.8" "-source" "1.8"]}}
  :test-selectors {:stress :stress
                   :default (complement :stress)}
  :plugins [[lein-codox "0.10.3"]
            [lein-jammin "0.1.1"]
            [ztellman/lein-cljfmt "0.1.10"]]
  :cljfmt {:indents {#".*" [[:inner 0]]}}
  :codox {:source-uri "https://github.com/clj-commons/byte-streams/blob/master/{filepath}#L{line}"
          :metadata {:doc/format :markdown}
          :namespaces [byte-streams]}
  :global-vars {*warn-on-reflection* true}
  :java-source-paths ["src"]
  :jvm-opts ^:replace ["-server" "-Xmx4g"])
