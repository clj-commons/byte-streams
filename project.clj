(defproject org.clj-commons/byte-streams (or (System/getenv "PROJECT_VERSION") "0.3.2")
  :description "A simple way to handle the menagerie of Java byte representations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]
  :dependencies [[org.clj-commons/primitive-math "1.0.0"]
                 [manifold/manifold "0.3.0"]
                 [potemkin "0.4.6"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.1"]
                                  [org.clojure/test.check "1.1.1"]
                                  [rhizome "0.2.9"]
                                  [criterium "0.4.6"]]}
             :ci {:dependencies [[org.clojure/clojure "1.11.1"]
                                 [org.clojure/test.check "1.1.1"]]}}
  :test-selectors {:stress :stress
                   :default (complement :stress)}
  :plugins [[jonase/eastwood "1.3.0"]
            [lein-cljfmt "0.9.0"]]
  :global-vars {*warn-on-reflection* true}
  :java-source-paths ["src"]
  :javac-options ["-target" "1.8" "-source" "1.8"]
  :jvm-opts ^:replace ["-server" "-Xmx4g"])
