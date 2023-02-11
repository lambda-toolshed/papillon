(ns lambda-toolshed.papillon.async.core-async
  (:require [clojure.core.async :refer [<! go]]
            [lambda-toolshed.papillon.async :refer [Chrysalis]]
            [lambda-toolshed.papillon.util :refer [error?]]))

(extend-type
 #?(:clj  clojure.core.async.impl.protocols.Channel
    :cljs cljs.core.async.impl.channels/ManyToManyChannel)
  Chrysalis
  (eclose [this handler]
    (go
      (let [x (<! this)]
        (handler (cond
                   (error? x) x
                   :else x))))))

