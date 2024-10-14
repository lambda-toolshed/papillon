(ns lambda-toolshed.papillon.channel
  (:require [lambda-toolshed.papillon.protocols :as protocols])
  (:import (java.util.concurrent CompletableFuture)
           (java.util.function Function)))

(extend-protocol lambda-toolshed.papillon.protocols/Chrysalis
  CompletableFuture
  (emerge
    ([this] (deref this))
    ([this callback] (let [h-then (reify Function (apply [_ x] (callback x)))
                           h-catch (reify Function (apply [_ e] (callback (ex-cause e))))]
                       (.exceptionally (.thenApply this h-then) h-catch)))))
