(ns lambda-toolshed.papillon.async)

(defprotocol Chrysalis
  (eclose [this handler]))

#?(:cljs
   (do
     (extend-type js/Promise
       Chrysalis
       (eclose [this handler]
         (-> this
             (.catch (fn [err] err))
             (.then handler))))))
