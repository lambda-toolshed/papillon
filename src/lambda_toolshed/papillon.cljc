(ns lambda-toolshed.papillon
  (:require
   [clojure.core.async :refer [<! go go-loop take! put! chan]]
   [clojure.core.async.impl.protocols :refer [ReadPort]]
   [lambda-toolshed.papillon.async]
   #?@(:cljs ([goog.string :as gstring]
              goog.string.format))))

(def ^:private fmt
  #?(:clj format :cljs gstring/format))

(defprotocol InterceptorPromotable
  (promote [this] "Promote this to an interceptor"))

(extend-protocol InterceptorPromotable
  #?(:clj clojure.lang.Var :cljs cljs.core/Var) (promote [this] (deref this))
  #?@(:clj (clojure.lang.IPersistentMap (promote [this] this))
      :cljs (cljs.core/PersistentArrayMap (promote [this] this)
                                          cljs.core/PersistentHashMap (promote [this] this)
                                          cljs.core/PersistentTreeMap (promote [this] this)))
  ;; TODO: extend the default in cljs
  #?(:clj Object :cljs object) (promote [this] (throw (ex-info "Not promoteable to an Interceptor!"
                                                               {:obj this :type (type this)}))))
(defn enqueue
  [ctx ixs]
  (update ctx ::queue into (map promote) ixs))

(defn- error?
  "Is the given value `x` an exception?"
  [x]
  #?(:clj (instance? Throwable x)
     :cljs (instance? js/Error x)))

(defn clear-queue
  "Empty the interceptor queue of the given context `ctx`, thus ensuring no
  further processing of the `enter` chain is attempted."
  [ctx]
  (update ctx ::queue empty))

(defn- context? [obj] (= (-> obj meta :type) ::ctx))

(defn- transition
  "Transition the context `ctx` to the candidate context value `candidate`.  This function
  works synchronously with value candidates -any async processing should be performed prior
  to invoking this function."
  [ctx candidate-ctx tag]
  (cond
    (-> candidate-ctx reduced?) (-> ctx
                                    (transition (unreduced candidate-ctx) tag)
                                    clear-queue)
    (-> candidate-ctx error?) (-> ctx
                                  (assoc ::error candidate-ctx)
                                  clear-queue)
    (-> candidate-ctx context? not) (-> ctx
                                        (assoc ::error (ex-info (fmt "Context was lost at %s!" tag)
                                                                {::tag tag
                                                                 ::ctx ctx
                                                                 ::candidate-ctx candidate-ctx}))
                                        clear-queue)
    :else candidate-ctx))

(defn- try-stage
  "Try to invoke the stage function at the `stage` key of the interceptor `ix`
  with the context `ctx` and return the result.  If there is no value at the
  `stage` key the context is returned unchanged.

  If the `:lambda-toolshed.papillon/trace` key is present then stage function
  invocations are conj'd onto its value."
  [{trace ::trace :as ctx} ix stage]
  (let [tag [(or (:name ix) (-> ix meta :name)) stage]
        ctx (if trace
              (update ctx ::trace conj tag)
              ctx)]
    (let [f (or (stage ix) identity)]
      (try
        (let [res (f ctx)]
          (if (satisfies? ReadPort res)
            (go (transition ctx (<! res) tag))
            (transition ctx res tag)))
        (catch #?(:clj Throwable :cljs :default) err
          (transition ctx err tag))))))

(defn- enter
  "Run the queued enter chain in the given context `ctx`.  If the
  `:lambda-toolshed.papillon/error` key is present in `ctx` then clear the queue
  (thus terminating the `enter` chain) and start processing the `:error` chain.
  If `ctx` is reduced (per `clojure.core/reduced?`) an early return is inferred
  and the unreduced `ctx` is used to start processing the `:leave` chain.

  `enter` also 'collapses' nested async calls by recurisvely calling
  itself with the value taken from the channel to ensure that if a
  channel was returned, it doesn't halt if that channel is another
  channel, but continues until it gets a non-ReadPort value for the
  context."
  [ctx]
  (if (satisfies? ReadPort ctx)
    (go (enter (<! ctx)))
    (if-let [ix (peek (::queue ctx))]
      (recur (-> ctx
                 (update ::queue pop)
                 (update ::stack conj ix)
                 (try-stage ix :enter)))
      ctx)))

(defn- leave
  "Runs the stacked `:leave` chain or `:error` chain in the given context `ctx`.
  if there is a value at the `:lambda-toolshed.papillon/error` key in `ctx` then
  the `:error` chain is run, otherwise the `:leave` chain is run.

  You should remove the `:lambda-toolshed.papillon/error` key from the returned
  context if you handle the error.  This will stop processing the `:error` chain
  and start processing the `:leave` chain in the stack of interceptors."
  [ctx]
  (if (satisfies? ReadPort ctx)
    (go (leave (<! ctx)))
    (if-let [ix (peek (::stack ctx))]
      (recur (-> ctx
                 (update ::stack pop)
                 (try-stage ix (if (::error ctx) :error :leave))))
      ctx)))

(defn- init-ctx
  "Inialize the given context `ctx` with the necessary data structures to
  process the interceptor chain `ixs`."
  [ctx ixs]
  (-> ctx
      (assoc ::queue #?(:clj clojure.lang.PersistentQueue/EMPTY
                        :cljs cljs.core/PersistentQueue.EMPTY))
      (assoc ::stack [])
      (vary-meta assoc :type ::ctx)
      (enqueue ixs)))

(defn- present-sync
  [result]
  (if-let [error (::error result)]
    (throw error)
    result))

(defn- present-async
  [result]
  (go-loop [ctx result]
    (if (satisfies? ReadPort ctx)
      (recur (<! ctx))
      (or (::error ctx) ctx))))

(defn- namer [i ix]
  (if (:name ix)
    ix
    (vary-meta ix update :name (fnil identity (fmt "itx%02d" i)))))

(defn execute
  "Execute the interceptor chain `ixs` with the given initial context `ctx`.

  Run foward through the chain calling the functions associated with the
  `:enter` key (where it exists), while accumulating a stack of interceptors
  seen.

  When the `:enter` chain is exhausted, run the accumulated stack in reverse
  order invoking the function at the `:leave` or `:error` key based on the
  `:lambda-toolshed.papillon/error` key of the context.

  The initial context `ctx` is augmented with the necessary housekeeping data
  structures before processing the chain.  If no context is provided an empty
  map is used.

  Note: Starting with an initial context that contains the key
  `:lambda-toolshed.papillon/error` is undefined and may change.

  Executing the interceptor chain can complete synchronously or asynchronously.
  If any interceptor function (enter, leave or error) completes asynchronously,
  then chain execution will complete asynchronously, otherwise the chain execution
  will complete synchronously.

  The result of executing the chain is either:

  1. (async) a channel whose sole value is the chain execution result or an
     exception (when not handled by the chain).
  2. (sync) the chain execution result, or a thrown exception (when not handled
     by the chain)."
  ([ixs]
   (execute ixs {}))
  ([ixs ctx]
   (let [ixs (map-indexed namer ixs)
         ctx (init-ctx ctx ixs)
         result (leave (enter ctx))]
     (if (satisfies? ReadPort result)
       (present-async result)
       (present-sync result)))))
