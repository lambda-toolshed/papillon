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
                         :cljs cljs.core/PersistentQueue.EMPTY))
       (assoc ::stack [])
       (assoc ::stage :enter)
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
    (-> candidate-ctx reduced?) (-> ctx
                                    (transition tag (unreduced candidate-ctx))
                                    clear-queue)
    (-> candidate-ctx error?) (-> ctx
                                  (assoc ::error candidate-ctx)
                                  (assoc ::stage :error)
                                  clear-queue)
    (-> candidate-ctx context? not) (let [e (ex-info (fmt "Context was lost at %s!" tag)
                                                     {::tag tag
                                                      ::ctx ctx
                                                      ::candidate-ctx candidate-ctx})]
                                      (transition ctx tag e))
    :else (let [{::keys [error stage]} candidate-ctx]
            (if (and (not error) (= stage :error))
              (assoc candidate-ctx ::stage :leave)
              candidate-ctx))))

(defn- move
  [{::keys [queue stack stage trace] :as ctx}]
  (case stage
    :enter (if-let [ix (peek queue)]
             [ix (cond-> ctx
                   true (update ::queue pop)
                   true (update ::stack conj ix)
                   trace (update ::trace conj [(identify ix) stage]))]
             (-> ctx (assoc ::stage :leave) move))
    (:error :leave) (if-let [ix (peek stack)]
                      [ix (cond-> ctx
                            true (update ::stack pop)
                            trace (update ::trace conj [(identify ix) stage]))]
                      [nil ctx])))

(defn- ix-sync
  [ctx]
  (let [[ix {::keys [stage] :as ctx}] (move ctx)]
    (if ix
      (let [f (or (ix stage) identity)
            f (fn [ctx] (try (f ctx) (catch #?(:clj Throwable :cljs :default) e e)))
            tag [(identify ix) stage]
            jump (partial transition ctx tag)]
        (recur (-> ctx f emerge jump)))
      (if-let [e (::error ctx)] (throw e) ctx))))

(defn- ix-async
  [ctx callback]
  (let [[ix {::keys [stage] :as ctx}] (move ctx)]
    (if ix
      (let [f (or (ix stage) identity)
            f (fn [ctx] (try (f ctx) (catch #?(:clj Throwable :cljs :default) e e)))
            tag [(identify ix) stage]
            jump (partial transition ctx tag)
            continue (comp #(ix-async % callback) jump)]
        (-> ctx f (emerge continue)))
      (callback (or (::error ctx) ctx)))))

(defn execute
  "Execute the interceptor chain within the given context `ctx`.

  Run forward through the chain calling the functions associated with the
  `:enter` key (where it exists), while accumulating a stack of interceptors
  seen.

  When the `:enter` chain is exhausted, run the accumulated stack in reverse
  order invoking the function at the `:leave` or `:error` key based on the
  `:lambda-toolshed.papillon/error` key of the context.

  The initial context `ctx` must include the necessary housekeeping data
  structures before processing the chain.  See `initialize`.

  Async Mode: this function returns nil and `callback` is invoked asynchronously
  with the resulting context.  All interceptors must return context values or
  deferred context values that can be realized without blocking (via the two-
  arity version of the Chrysalis protocol).

  Sync Mode: this function returns the resulting context synchronously, possibly
  blocking while waiting for deferred contexts to be realized.  All interceptors
  must return context values or deferred context values that can be realized
  synchronously (via the single-arity version of the Chryslis protocol).

  Returns either (Sync Mode) the resulting context of executing the chain or
  (Async Mode) nil."
  ;; synchronous execution
  ([ctx]
   {:pre [(= ::ctx (-> ctx meta :type))]}
   (ix-sync ctx))
  ;; asynchronous execution
  ([ctx callback]
   {:pre [(= ::ctx (-> ctx meta :type)) (fn? callback)]}
   (ix-async ctx callback)
   nil))
