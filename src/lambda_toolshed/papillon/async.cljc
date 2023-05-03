(ns lambda-toolshed.papillon.async)

(defprotocol Chrysalis
  "Protocol for a candidate context object, to allow for uniform handling between synchronous and asynchronous interceptor results."
  (emerge [this handler] "Unwraps the potentially deferred computation represented by `this` and passes it to the `handler`."))

(extend-protocol Chrysalis
  nil
  (emerge [this handler]
    (handler this)))

#?(:clj
   (extend-protocol Chrysalis
     clojure.lang.Reduced
     (emerge [this handler]
       (handler this))))

#?(:cljs
   (extend-protocol Chrysalis
     Reduced
     (emerge [this handler]
       (handler this))))

#?(:clj
   (extend-protocol Chrysalis
     clojure.lang.IDeref
     (emerge [this handler]
       (handler @this))))

#?(:clj
   (extend-protocol Chrysalis
     Object
     (emerge [this handler]
       (handler this))))

#?(:cljs
   (extend-protocol Chrysalis
     object
     (emerge [this handler]
       (handler this))))

#?(:cljs
   (do
     (extend-type js/Promise
       Chrysalis
       (emerge [this handler]
         (js/Promise. (fn [resolve reject]
                        (let [wrapped-handler (fn [x]
                                                (try
                                                  (let [res (handler x)]
                                                    (resolve res)
                                                    res)
                                                  (catch :default err
                                                    (reject err)
                                                    err)))]
                          (.then this #(emerge % wrapped-handler) #(emerge % wrapped-handler)))))))))
