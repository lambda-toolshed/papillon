(ns lambda-toolshed.papillon
  (:require
   [clojure.core.async :refer [<! go go-loop take! put! chan]]
   [clojure.core.async.impl.protocols :refer [ReadPort]]
   [lambda-toolshed.papillon.async]))

;;;; TODO: Official Support Interceptor names...
;;;;   format-context to show names?
;;;;   tracing with adding a name and stage call???
;;;;     - remove by name ?
;;;;     - insert before ?
;;;;     - insert after ?

;;;; TODO: Support logging/tracing?
;;;;   interceptor log protocol??
;;;;   support adding :before-stage and :after-stage callback as part of context?

(defn into-queue
  ([xs]
   (into-queue nil xs))
  ([q xs]
   ((fnil into #?(:clj clojure.lang.PersistentQueue/EMPTY
                  :cljs cljs.core/PersistentQueue.EMPTY)) q xs)))

(defn enqueue
  [ctx ixs]
  (update-in ctx [:lambda-toolshed.papillon/queue] into-queue ixs))

(defn- error?
  "Check if this is an exception."
  [x]
  #?(:clj (instance? Throwable x)
     :cljs (instance? js/Error x)))

(defn- async-catch
  "Takes a value from a channel, and checks if it is an error type value.
   If the result is an error type value add that to the previous context
   under the `:lambda-toolshed.papillon/error` key and use that new result as the context.  Otherwise
   use the value returned from the channel as the context."
  [ctx res]
  (go
    (let [x (<! res)]
      (if (error? x)
        (assoc ctx :lambda-toolshed.papillon/error x)
        x))))

(defn- try-stage
  "Try to invoke a stage on an interceptor with a context.
   If the stage is not present, it means the interceptor does not support
   this stage, so return the context and proceed to the next interceptor
   in the chain.

   This also catches any errors that are raised (from synchronous calls)
   and adds the error to the original context under the key `:lambda-toolshed.papillon/error`."
  [ctx ix stage]
  (if-let [f (stage ix)]
    (try
      (let [res (f ctx)]
        (if (satisfies? ReadPort res)
          (go (<! (async-catch ctx res)))
          res))
      (catch #?(:clj Throwable :cljs :default) err
        (assoc ctx :lambda-toolshed.papillon/error err)))
    ctx))

(defn clear-queue
  "Clear out the queue so that no further items in the enter chain are
  processed.  Primarily used so one doesn't have to worry about
  namespaced keywords."
  [ctx]
  (dissoc ctx :lambda-toolshed.papillon/queue))

(defn- enter
  "Runs the enter chain.  If the key :lambda-toolshed.papillon/error is present in the context
  we stop the `enter` chain and proceed to the next stage.  If the
  context is reduced, we unreduce the context and proceed to the next
  stage.  Reducing the context allows us to have an early return
  mechanism without causing users to resort to throwing errors and
  then immediately handling them, or having to worry about clearing
  out the queue themselves.

  `enter` also 'collapses' nested async calls by recurisvely calling
  itself with the value taken from the channel to ensure that if a
  channel was returned, it doesn't halt if that channel is another
  channel, but continues until it gets a non-ReadPort value for the
  context."
  [ctx result-chan]
  (cond
    (satisfies? ReadPort ctx) (take! ctx #(enter % result-chan))
    (:lambda-toolshed.papillon/error ctx) (put! result-chan  (clear-queue ctx))
    (reduced? ctx) (put! result-chan (clear-queue (unreduced ctx)))
    :else (let [queue (:lambda-toolshed.papillon/queue ctx)]
            (if (empty? queue)
              (put! result-chan ctx)
              (let [ix (peek queue)
                    new-queue (pop queue)
                    new-stack (conj (:lambda-toolshed.papillon/stack ctx) ix)]
                (recur (-> ctx
                           (assoc :lambda-toolshed.papillon/queue new-queue
                                  :lambda-toolshed.papillon/stack new-stack)
                           (try-stage ix :enter)) result-chan))))))

(defn- leave
  "Runs the leave and error chain.  `leave` will run the `:lambda-toolshed.papillon/error`
  key function in the interceptor if there is an `:lambda-toolshed.papillon/error` in the context.

  If there is no `:lambda-toolshed.papillon/error` key in the context, it will run the function
  under the `:leave` key in the interceptor.

  If your interceptor had decided to handle the error in the context, it
  should remove the `:lambda-toolshed.papillon/error` key from the context, and allow any remaining
  interceptors to run their `:leave` functions, unless one of them throws
  an error.

  If the function under the `:enter` key 'opened' a resource, you will
  want to ensure it is closed in both the `:lambda-toolshed.papillon/error` and `:leave` case, as either path may be taken on the way back up the interceptor chain."
  [in result-chan]
  (if (satisfies? ReadPort in)
    (take! in #(leave % result-chan))
    (let [stack (:lambda-toolshed.papillon/stack in)]
      (if (empty? stack)
        (put! result-chan in)
        (let [ix (peek stack)
              new-stack (pop stack)
              stage (if (:lambda-toolshed.papillon/error in) :error :leave)]
          (recur (-> in
                     (assoc :lambda-toolshed.papillon/stack new-stack)
                     (try-stage ix stage))
                 result-chan))))))
(defn- init-ctx
  "Sets up the context with the queue key and the stack key.

  The queue is for the forward processing of items, and the stack
  is what is used to trace backwards through the interceptor stack"
  [ctx ixs]
  (assoc (enqueue ctx ixs)
         :lambda-toolshed.papillon/stack []))

(defn- await-result
  "'Unwinds' any nested channels and returns the context"
  [res]
  (go-loop [ctx res]
    (if (satisfies? ReadPort ctx)
      (recur (<! ctx))
      ctx)))

(defn execute
  "Executes the interceptor call chain as a queue.

  It will run foward through the chain calling the function
  associated to the `:enter` key where that where that function
  exists, while adding the interceptor to the history of
  interceptors seen, so when the :enter chain is
  finished, it can run backwards through the history
  (reverse order) to apply the functions associated to `:leave`,
  or `:error`, as determined by the `:lambda-toolshed.papillon/error` key
  on the context.

  `execute` takes any map as an initial context, and will associate
  the queue and stack into the context to start processing.  If no
  context is provided an empty map is used.  Note: The behavior of
  starting with an initial context that contains the key
  `:lambda-toolshed.papillon/error` is left unspecified and may be subject to
  change."
  ([ixs]
   (execute {} ixs))
  ([ctx ixs]
   (let [ctx (init-ctx ctx ixs)
         enter-res (chan 1)
         leave-res (chan 1)]
     (enter ctx enter-res)
     (leave enter-res leave-res)
     (await-result leave-res))))
