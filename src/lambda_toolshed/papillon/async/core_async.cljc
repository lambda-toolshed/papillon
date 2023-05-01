(ns lambda-toolshed.papillon.async.core-async
  (:require
   #?(:cljs [cljs.core.async.impl.channels])
   [clojure.core.async :refer [<! go-loop]]
   [clojure.core.async.impl.protocols :refer [ReadPort]]
   [lambda-toolshed.papillon.async :refer [Chrysalis]]))

(extend-type
 #?(:clj  clojure.core.async.impl.protocols.Channel
    :cljs cljs.core.async.impl.channels/ManyToManyChannel)
  Chrysalis
  (emerge [this handler]
    (go-loop [res this]
      (let [x (<! res)]
        (if (satisfies? ReadPort x)
          (recur x)
          (try
            (handler x)
            (catch #?(:clj Throwable :cljs :default) err
              err)))))))
