(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b] ; for b/git-process
            [org.corfield.build :as bb]))

(def lib 'com.github.lambda-toolshed/papillon)

(def version
  (-> (b/git-process {:git-args "describe --abbrev=4 --dirty"})
      (or "0.0.0")
      (str/replace #"^v" "")))

(defn test-clj "Run the Clojure tests." [opts]
  (-> opts
      (bb/run-tests)))

(defn test-cljs "Run the ClojureScript tests." [opts]
  (-> opts
      (bb/run-task [:test :project/test-cljs])))

(defn test-all "Run all the tests." [opts]
  (-> opts
      (test-clj)
      (test-cljs)))

(defn lint "Run the linter." [opts]
  (-> opts
      (bb/run-task [:lint/kondo])))

(defn ensure-format "Run the formatter." [opts]
  (-> opts
      (bb/run-task [:project/format])))

(defn format-check "Run the formatter for validation." [opts]
  (-> opts
      (bb/run-task [:project/format-check])))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (format-check)
      (lint)
      (test-all)
      (bb/clean)
      (bb/jar)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))
