(defproject phi "0.1.0-SNAPSHOT"
  :description "A library for fluxing the frontend"
  :url "https://github.com/potetm/phi"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ^:replace ["-Xms1g" "-Xmx1g" "-server"]
  :dependencies [[com.facebook/react "0.11.2"]
                 [cljs-uuid "0.0.4"]
                 [datascript "0.5.1"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [sablono "0.2.22"]]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :cljsbuild {:builds
              [{:id           "dev"
                :source-paths ["src"]
                :compiler     {:optimizations :whitespace
                               :pretty-print  true
                               :preamble      ["react/react.js"]
                               :output-to     "target/phi.js"}}]})
