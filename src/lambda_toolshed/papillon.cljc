(ns lambda-toolshed.papillon
  (:require [lambda-toolshed.papillon.protocols :refer [promote emerge]]
            #?@(:cljs ([goog.string :as gstring]
                       goog.string.format))))

(def ^:private fmt
  #?(:clj format :cljs gstring/format))

(defn- identify
  "Return the string identifier of the given interceptor `ix`."
  [ix]
  (or (:name ix) (str "ix-" (hash ix))))

(defn enqueue
  "Add the given inteceptors `ixs` to the given context `ctx`."
  [ctx ixs]
  (update ctx ::queue into (map promote) ixs))

(defn initialize
  "Inialize the given context `ctx` with the necessary data structures to
  process the interceptor chain `ixs`.  Starting with an initial context
  that contains the key `:lambda-toolshed.papillon/error` is undefined."
  ([ixs] (initialize ixs {}))
  ([ixs ctx]
   (-> ctx
       (vary-meta assoc :type ::ctx)
       (assoc ::queue #?(:clj clojure.lang.PersistentQueue/EMPTY
                         :cljs cljs.core/PersistentQueue.EMPTY)
              ::stack []
              ::stage :enter)
       (enqueue ixs))))

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
  "Transition the context `ctx` to the candidate context value `candidate`.
  This function works synchronously with value candidates -any async processing
  should be performed prior to invoking this function."
  [ctx tag candidate-ctx]
  (cond
    (reduced? candidate-ctx) (-> ctx
                                 (transition tag (unreduced candidate-ctx))
                                 clear-queue)
    (error? candidate-ctx) (-> ctx
                               (assoc ::error candidate-ctx)
                               clear-queue)
    (context? candidate-ctx) candidate-ctx
    :else (let [e (ex-info (fmt "Context was lost at %s!" tag)
                           {::tag tag
                            ::ctx ctx
                            ::candidate-ctx candidate-ctx})]
            (transition ctx tag e))))

(defn- move
  "Transition the given context `ctx` to the next state by modifying the queue,
  stack and stage."
  [{::keys [queue stack stage error handled?] :as ctx}]
  (case stage
    :enter (if-let [ix (peek queue)]
             (-> ctx
                 (update ::queue pop)
                 (update ::stack conj ix))
             (-> ctx
                 (assoc ::stage (if error :error :leave))))
    :leave (-> ctx (assoc ::stage (if error :error :final)))
    :error (-> ctx (assoc ::stage :final))
    :final (-> ctx
               (update ::stack pop)
               (assoc ::stage (if error :error :leave)))))

(defn- evaluate
  "Evaluate the next operation of the given context `ctx`. Returns a three-tuple
  of the context prior to interceptor execution, the emerge-able result of
  interceptor execution and a tag documenting the execution."
  [ctx]
  (let [{::keys [stage stack] :as ctx} (move ctx)]
    (when-let [ix (peek stack)]
      (let [tag [(identify ix) stage]
            f (or (ix stage) identity)
            ctx (update ctx ::trace (fn [t] (when t (conj t tag))))
            obj (try (f ctx) (catch #?(:clj Throwable :cljs :default) e e))]
        [ctx obj tag]))))

(defn- execute-sync
  "Recursively execute the given context `ctx`, synchronously returning the
  resulting context or throwing if an exception is not handled by the chain.

  The initial context `ctx` must include the necessary housekeeping data
  structures before processing the chain.  See `initialize`."
  [ctx]
  (if-let [[ctx obj tag] (evaluate ctx)]
    (let [jump (partial transition ctx tag)]
      (recur (-> obj emerge jump)))
    (if-let [e (::error ctx)] (throw e) ctx)))

(defn- execute-async
  "Recursively execute the given context `ctx`, calling the given `callback`
  asynchronously with the resulting context or exception, if not handled by
  the chain.

  The initial context `ctx` must include the necessary housekeeping data
  structures before processing the chain.  See `initialize`."
  [ctx callback]
  (if-let [[ctx obj tag] (evaluate ctx)]
    (let [jump (partial transition ctx tag)
          continue (comp #(execute-async % callback) jump)]
      (emerge obj continue))
    (callback (or (::error ctx) ctx))))

(defn execute
  "Execute the interceptor chain within the given context `ctx`.  If the
  function `callback` is provided, run in async mode, otherwise run in
  sync mode.

  Run forward through the chain calling the functions associated with the
  `:enter` key (where it exists), while accumulating a stack of interceptors
  seen.  When the `:enter` chain is exhausted, run the accumulated stack in
  reverse order.

  Async Mode: this function returns nil without blocking and `callback` is
  invoked (possibly on the calling thread, if no interceptor in the chain
  returns a value that must be emerged asynchronously).  Each interceptor must
  return a (possibly deferred) context that can be realized without blocking
  (via the two-arity version of the Chrysalis protocol).  Always returns nil.

  Sync Mode: this function returns the resulting context synchronously, possibly
  blocking while waiting for deferred contexts to be realized.  Each interceptor
  must return a (possibly deferred) context that can be realized synchronously
  (via the single-arity version of the Chryslis protocol).  Returns either the
  result context, or throws any unhandled exception."
  ([ixs ctx] ; sync mode
   {:pre [(sequential? ixs) (map? ctx)]}
   (execute-sync (initialize ixs ctx)))
  ([ixs ctx callback] ; async mode
   {:pre [(sequential? ixs) (map? ctx) (fn? callback)]}
   (execute-async (initialize ixs ctx) callback)
   nil))
