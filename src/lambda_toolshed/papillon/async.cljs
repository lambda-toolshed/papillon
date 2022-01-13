(ns lambda-toolshed.papillon.async
  (:require [clojure.core.async :as core.async]
            [clojure.core.async.impl.protocols :refer [ReadPort]]
            [cljs.core.async.interop :as core.async.interop :refer [p->c]]))

(extend-type js/Promise
  ReadPort
  (take! [this handler]
    (->
     this
     p->c
     (#(core.async/take 1 %))
     (clojure.core.async.impl.protocols/take! handler))))
