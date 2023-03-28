(ns lambda-toolshed.papillon.async)

(defprotocol Chrysalis
  (emerge [this handler]))

#?(:cljs
   (do
     (extend-type js/Promise
       Chrysalis
       (emerge [this handler]
         (-> this
             (.catch (fn [err] err))
             (.then handler))))))
