(ns lambda-toolshed.papillon.async.core-async
  (:require
   #?(:cljs [cljs.core.async.impl.channels])
   [clojure.core.async :refer [<! chan go-loop put!]]
   [clojure.core.async.impl.protocols :refer [ReadPort]]
   [lambda-toolshed.papillon.async :refer [Chrysalis emerge]]))

(extend-type
 #?(:clj  clojure.core.async.impl.protocols.Channel
    :cljs cljs.core.async.impl.channels/ManyToManyChannel)
  Chrysalis
  (emerge [this handler]
    (let [c (chan 1)]
      (go-loop [res this]
        (let [x (<! res)]
          (if (satisfies? ReadPort x)
            (recur x)
            (emerge x (fn [x]
                        (put! c (try
                                  (handler x)
                                  (catch #?(:clj Throwable :cljs :default) err
                                    err))))))))
      c)))
