(ns lambda-toolshed.papillon.protocols)

(defprotocol InterceptorPromotable
  (promote [this] "Promote this to an interceptor"))

(extend-protocol InterceptorPromotable
  #?(:clj clojure.lang.Var :cljs cljs.core/Var)
  (promote [this] (deref this))
  #?@(:clj (clojure.lang.IPersistentMap
            (promote [this] this))
      :cljs (cljs.core/PersistentArrayMap
             (promote [this] this)
             cljs.core/PersistentHashMap
             (promote [this] this)
             cljs.core/PersistentTreeMap
             (promote [this] this)))
  ;; TODO: extend the default in cljs
  #?(:clj Object :cljs object)
  (promote [this] (throw (ex-info "Not possible to promote this object to an Interceptor!"
                                  {:obj this :type (type this)}))))

(defprotocol Chrysalis
  (emerge
    [this]
    [this callback]
    "Realize this Chrysalis (deferred value).  In the single-arity version the realized value is synchronously returned, possibly blocking while awaiting its availability; in the two-arity version blocking is not allowed and the realized value is passed to the provided callback."))

(extend-protocol Chrysalis
  #?(:clj Object :cljs object)
  (emerge ([this] this)
    ([this callback] (callback this)))
  nil
  (emerge ([this] this)
    ([this callback] (callback this))))

#?(:clj (extend-protocol Chrysalis
          clojure.lang.IPending
          (emerge ([this] (deref this))
            ([this callback] (future (-> this deref callback))))))

#?(:cljs
   (extend-protocol Chrysalis
     js/Promise
     (emerge
       ([this] (throw (ex-info "A deferred context cannot be resolved synchronously!"
                               {::this this ::type (type this)})))
       ([this f] (.then this f)))))
