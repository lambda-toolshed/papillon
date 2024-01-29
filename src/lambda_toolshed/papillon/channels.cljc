(ns lambda-toolshed.papillon.channels
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.channels :as channels]
            [lambda-toolshed.papillon.protocols :as protocols]))

(extend-type #?(:clj  clojure.core.async.impl.protocols.Channel
                :cljs cljs.core.async.impl.channels/ManyToManyChannel)
  protocols/Chrysalis
  (emerge
    #?(:clj ([this] (async/<!! this)))
    ([this f] (async/take! this f))))
