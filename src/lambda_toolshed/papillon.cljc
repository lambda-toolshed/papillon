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
  [ctx ixs]
  (update ctx ::queue into (map promote) ixs))

(defn initialize
  "Inialize the given context `ctx` with the necessary data structures to process
   the interceptor chain `ixs`.  Note: starting with an initial context that
   contains the key `:lambda-toolshed.papillon/error` is undefined and may change."
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
  "Transition the context `ctx` to the candidate context value `candidate`.  This function
  works synchronously with value candidates -any async processing should be performed prior
  to invoking this function."
  [ctx tag candidate-ctx]
  (cond
    (reduced? candidate-ctx) (-> ctx
                                 (transition tag (unreduced candidate-ctx))
                                 clear-queue)
    (error? candidate-ctx) (-> ctx
                               (assoc ::error candidate-ctx
                                      ::stage :error)
                               clear-queue)
    (context? candidate-ctx) (let [{::keys [error stage]} candidate-ctx]
                               (if (and (not error) (= stage :error))
                                 (assoc candidate-ctx ::stage :leave)
                                 candidate-ctx))
    :else (let [e (ex-info (fmt "Context was lost at %s!" tag)
                           {::tag tag
                            ::ctx ctx
                            ::candidate-ctx candidate-ctx})]
            (transition ctx tag e))))

(defn- move
  "Move to the next interceptor in the interceptor chain within `ctx`.  Returns
  a tuple of the resulting context, the current interceptor and a descriptive
  tag of the move operation.  Returns nil if the chain has been consumed."
  [{::keys [queue stack stage trace] :as ctx}]
  (case stage
    :enter (if-let [ix (peek queue)]
             (let [tag [(identify ix) stage]
                   ctx (cond-> ctx
                         true (update ::queue pop)
                         true (update ::stack conj ix)
                         trace (update ::trace conj tag))]
               [ctx ix tag])
             (-> ctx (assoc ::stage :leave) move))
    (:error :leave) (if-let [ix (peek stack)]
                      (let [tag [(identify ix) stage]
                            ctx (cond-> ctx
                                  true (update ::stack pop)
                                  trace (update ::trace conj tag))]
                        [ctx ix tag])
                      nil)))

(defn- evaluate
  "Evaluate the interceptor `ix` with the context `ctx`."
  [ix {::keys [stage] :as ctx}]
  (if-let [f (ix stage)]
    (try (f ctx) (catch #?(:clj Throwable :cljs :default) e e))
    ctx))

(defn- execute-sync
  [ctx]
  (if-let [[ctx ix tag] (move ctx)]
    (let [obj (evaluate ix ctx)
          jump (partial transition ctx tag)]
      (recur (-> obj emerge jump)))
    (if-let [e (::error ctx)] (throw e) ctx)))

(defn- execute-async
  [ctx callback]
  (if-let [[ctx ix tag] (move ctx)]
    (let [obj (evaluate ix ctx)
          jump (partial transition ctx tag)
          continue (comp #(execute-async % callback) jump)]
      (emerge obj continue))
    (callback (or (::error ctx) ctx))))

(defn execute
  "Execute the interceptor chain within the given context `ctx`.  If the
  function `callback` is provided, run in async mode, otherwise run in
  sync mode.

  Run forward through the chain calling the functions associated with the
  `:enter` key (where it exists), while accumulating a stack of interceptors
  seen.

  When the `:enter` chain is exhausted, run the accumulated stack in reverse
  order invoking the functions at the `:leave` or `:error` key based on the
  presence of a value at the `:lambda-toolshed.papillon/error` key in the
  context.

  The initial context `ctx` must include the necessary housekeeping data
  structures before processing the chain.  See `initialize`.

  Async Mode: this function returns nil without blocking and `callback` is
  invoked (possibly on the calling thread, if no interceptor in the chain
  returns a value that must be emerged asynchronously).  Each interceptor must
  return a (possibly deferred) context that can be realized without blocking
  (via the two-arity version of the Chrysalis protocol).

  Sync Mode: this function returns the resulting context synchronously, possibly
  blocking while waiting for deferred contexts to be realized.  Each interceptor
  must return a (possibly deferred) context that can be realized synchronously
  (via the single-arity version of the Chryslis protocol).

  Returns either the resulting context of executing the chain (Sync Mode) or
  nil (Async Mode)."
  ([ixs ctx] ; sync mode
   {:pre [(sequential? ixs) (map? ctx)]}
   (execute-sync (initialize ixs ctx)))
  ([ixs ctx callback] ; async mode
   {:pre [(sequential? ixs) (map? ctx) (fn? callback)]}
   (execute-async (initialize ixs ctx) callback)
   nil))
