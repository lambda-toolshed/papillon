(ns lambda-toolshed.papillon.async
  #?(:cljs
     (:require [clojure.core.async :as core.async]
               [clojure.core.async.impl.protocols :refer [ReadPort]]
               [cljs.core.async.interop :as core.async.interop :refer [p->c]])))

(defprotocol Chrysalis
  (emerge [this handler]))

#?(:cljs
   (do
     (extend-type js/Promise
       Chrysalis
       (emerge [this handler]
         (-> this
             (.catch identity)
             (.then handler))))))
