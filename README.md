# Papillon

An interceptor library for Clojure.

<img src="./papillon.png" alt="Butterfly with Lisp Lambdas for the dots on the wings" height="300"/>


## About

This library was inspired by the mix of Interceptor patterns from:

 - Pedestal's [Interceptors](http://pedestal.io/reference/interceptors),
 - Metosin's [Sieppari](https://github.com/metosin/sieppari),
 - Eric Normand's [A Model of Interceptors](https://lispcast.com/a-model-of-interceptors/),
 - and LambdaIsland's [Interceptors, part 1, concepts](https://lambdaisland.com/episodes/interceptors-concepts).

### Why this library?

We use Clojure(Script) on AWS Lambdas where a number of them are triggered asynchronously without a HTTP request.  The goal of this library is to advocate for the idea of interceptors, while providing a way to use them that are independent from HTTP and applicable in a broader range of scenarios.

A side goal of this is to provide the core of the execution of an interceptor queue, while keeping it open enough that it can be composed with other complimentary libraries without locking the consumer into using specific libraries required for other aspects, such as picking a logging library.

## How it works

### Summary

If Either Monads are burritos, then Interceptor Chains are like Dagwood sandwiches for a hungry caterpillar.

The interceptor chain pattern is an execution pipeline that runs forward to the end of the chain, and then turns around and goes back out the pipeline in reverse order, allowing you to specify things like how to clean up resources on the way back out of the pipeline.

This is the hungry caterpillar idea.

The caterpillar starts at the top of the sandwich, and eats its way down through all the layers of the sandwich, some of which may be empty like the holes in some delicious Swiss cheese, or items only on one side of the sandwich; then when the caterpillar has exited the bottom, it chomps its way back up to the top on the other half of the sandwich resulting in a well fed caterpillar that is now a beautiful butterfly.

Sometimes things go wrong, and the caterpillar eats something that didn't agree with it making it sick.  In that case, the caterpillar upon exiting that layer of the sandwich scurries to the outside of the sandwich, and starts walking up the outside crust taking nibbles of things here and there until something settles its indigestion, at which point it goes back into the sandwich once again, and continues to eat, or otherwise climbing back up the outside of the sandwich to the top where you get a sick caterpillar waiting for you.

<img src="./papillon-sandwich.png" alt="Drawing of a caterpillar atop a giant stacked sandwich with ingredients that go across the whole sandwich, and some that are on half only." height="1200"/>

### The Spec

Interceptors are represented as a map with the optional keys of `:enter`, `:leave`, and/or `:exit`.  None of the keys are required to be on an interceptor map, and if no truthy value for the key being checked is found on the interceptor map, the executor will continue its processing skipping over the interceptor for that stage.

The idea of sticking to a map instead of a record is that if the interceptor is a map, consumers can attach any other data to the interceptor, which the executor will ignored instead of actively discarding when converting to a record, allowing the extra keys and values on the interceptor map to be accessible while it exists on the queue or the stack in the context.

#### Completing the Enter Stage

##### Empty Queue of Interceptors

The enter stage is considered completed when there are no more interceptors on the queue, and will start processing the interceptor chain from the stack.

##### Reduced Context

If the returned context from an `:enter` function is a reduced value, this is treated as a signal to stop further processing of the :enter chain and proceed to start processing the interceptor stack through the `:leave` stage.

##### Raising, or Returning, an Error

When an interceptor function throws or returns an error, e.g. ExceptionInfo, Throwable in Clojure, js/Error in Clojurescript, the queue is cleared from the context, the error is added under the `:lambda-toolshed.papillon/error` key, and the stack starts getting processed through the `:error` stage.

The interceptor stack will continue to be consumed through the `:error` stage until the error is marked as resolved by removing the `:lambda-toolshed.papillon/error` key from the context map.  Once there is no error in the context, processing will proceed through the `:leave` stage until another error is returned or gets thrown in a synchronous interceptor.

#### The Context map

##### Keys

| key                                   | description                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------                                | --------------                                                                                                                                                                                                                                                                                                                                                                                                             |
| `:lambda-toolshed.papillon/queue` | The queue of interceptors.  This gets initialized with the interceptors passed to `execute`, but can be managed if you know what you are doing.                                                                                                                                                                                                                                                                            |
| `:lambda-toolshed.papillon/stack` | The stack of interceptors for traversing back through the chain of interceptors encountered.                                                                                                                                                                                                                                                                                                                               |
| `:lambda-toolshed.papillon/error` | This key should have the error information associated with it.  This key signifies we are in an error state, and interceptors with `:error` key will be processed, either for them to clean up some state (open connections, etc.) or attempt to handle and resolve the error and return nicely.  A few examples might be: turn the error into 500 HTTP response; put original message and error onto an error queue; etc. |


### Asynchronous Interceptors

The executor handles Asynchronous items by checking to see if the return value of an interceptor is a `ReadPort`.

#### Errors in asynchronous interceptors

To signal an error in an asynchronous interceptor, the executor cannot just catch an exception, as the error occurs in a different timeline from where the code that started the process lives, so any catch for an exception has fallen out of scope.

Because of this, the Interceptor library expects the asynchronous interceptors to catch any errors themselves and return the error as its return value.

After the executor "unwraps" the asynchronous result, if it finds an error in the unwrapped result then it will add the error to the context under `:lambda-toolshed.papillon/error` key, as if it had been caught from a synchronous interceptor.

#### ClojureScript and JavaScript interop

If you are in ClojureScript, and are in Promise land (Editors Note: this is drastically different from The Promised Land, and the two should not be confused), you can import the namespace `lambda-toolshed.papillon.async` which will have a Promise implement `ReadPort` and turn the Promise into something that can be read in the same way as a channel.

This is done by using `cljs.core.async.interop/p->c`, taking the `PromiseChannel` returned from that and turning it into a channel with the result being a single item on the channel.

## Examples and Other ways to extend usage

### Interceptor Tracing/Timing

Because the interceptor executor takes a sequence of interceptors to build the processing queue from, we can manipulate that before execution time as it is data.  In the example below, if we have tracing enabled, we interleave a tracing interceptor, could be a timing capture interceptor, with the standard interceptors we are expecting to process as part of the interceptor chain.

```clojure
;; trace is your function wrapping println, or logging framework tracing.
;; prettify-ctx is something that might show the important parts of the context
;;
;; see examples/example.clj

(defn prettify-ctx
  "'Prettfies' the context for nicer output in whatever manner that might mean."
  [ctx]
  ;; munge the context to make it pretty
  )

(letfn [(describe [ix] (or (:name ix) ix))]
  (def trace-ix
    {:name :trace-ix
     :enter (fn [ctx]
              (trace (str "enter" (prettify-ctx ctx)))
              ctx)
     :leave (fn [ctx]
              (trace (str "leaving" (prettify-ctx ctx)))
              ctx)
     :error (fn [ctx]
              (trace (str "error" (prettify-ctx ctx)))
              ctx)}))

(def ix-chain
 ;; your sequence of interceptors to run live here
 ;; imagine a nice big list 20+ lines long...
)

(defn with-tracing
  [ixs]
  (if (tracing-enabled?)
    (interleave (repeat trace-ix) ixs)
    ixs))

(defn run
  []
  (ix/execute {}
              (with-tracing ix-chains)
              (fn [ctx] (println "SUCCESS!!!!" ctx))
              (fn [ctx] (println "ERROR!!!!" ctx)))
```

### Nesting Interceptor Executions

By decoupling interceptors and keeping them generic, it opens up the possibility that you could nest interceptor chains within a larger context.

```clojure

(def some-nested-ixs
 [ ;;; some interceptor chain goes here
 ])

(def other-nested-ixs
 [ ;;; some other interceptor chain goes here
 ])

(defn fork
  [starting-ctx & ix-chains]
  ;; fork things off
  (let [successes-chan (chan)
        errors-chan (chan)]
    (doseq [ixs ix-chains]
      (evaluate starting-ctx
                ixs
                (fn (ctx) (put! successes-chan ctx))
                (fn (ctx) (put! errors-chan ctx))))
    [successes-chan errors-chan]))

(defn join
  "Join the results of calling fork to split off the channel
   and stitch them back up as needed"
 [& args]
 ;; join things back up (channels, etc.)
 )

(defn fork-join
 "Spin up mulitple interceptor executions (in channels)
  to give concurrency/asynchornicity and then join them
  back up together"
 [ctx]
 (join (fork (sub-context) some-nested-ixs other-nested-ixs)))

(def fork-join-ix
  {:enter fork-join})

```

While this is not a recommendation to do such nesting, it is meant to highlight additional possibilities of using the interceptor pattern decoupled from the HTTP Request/Response concept.

The decoupling allows the interceptor pattern to be used in a similar way that the Either monad type in ML family of languages can be used in functions where it becomes useful, instead of only at the outer most entry points of your application.

### A Possible Condition system

As interceptors themselves are maps, one could have any other sets of keys on the map used in the Interceptor queue, and in keeping with Clojure style, we only care about those keys we want.

By giving a contract for the interceptor and interceptor execution one could implement something similar in spirit to Common Lisp's Condition System by being able to look at the Interceptor Stack of calls, and walk back up the Stack without consuming it the way raising an error does.

Because you have visibility to the stack and the queue, as they are keys in the context map, you could walk up the stack looking for interceptors that have the key that was received as a "signal", and invoke a function associated with that key to attempt to handle the signal.  This allows you to unwind the stack, without unwinding the stack, because, as long as you don't return the modified context, you are working on a persistent data structure, and any modifications to the stack are a copy of the stack scoped to your usage.  Persistent Data Structures FOR THE WIN!!

A basic non-prod, pseudo code, example may look like:

```clojure
(defn signal
  [signal ctx & args]
  (loop ([ix & ix-stack] (stack ctx))
    (if ix
      (if-let [f (signal ix)]
        (apply f (conj ctx args))
        (recur ix-stack))
      (throw (ex-info "no signal handler found"
                      {:signal signal
                       :ctx ctx
                       :args args})))))

(defn retry-request
 [max-retry-count ctx request response]
 (let [url (:url request)
      retry-count ( (get-in ctx [::request-retries url]))]
   (if (> retry-count max-retry-count)
     (throw "Too Many Failures" {:retry-count retry-count
                                 :request-url url
                                 :ctx ctx})
     (-> ctx
       (update-in [::request-retries url] (fnil inc 0))
       requeue-current
       stack-pop-current))))

(def retry-ix
  {:http-request-failed (partial retry-request 3)})

(def request-ix
  {:enter (fn [ctx]
  (go
    (try
      (let [request (build-request ctx)
            response (<! (request! request))]
        (if (error? response)
          (signal :http-request-failed ctx request response) ;; look for :http-request-failed in an interceptor on the stack
          (assoc ctx :response response)))
      (catch #?(:clj Exception :cljs default) e
        e))))})

(defn run
  []
  (go
    (let [c (ix/execute {}
              [retry-ix
              request-ix])]
      (println (<! c)))))
```

This example could be extended to support keyword hierarchies and walk up the stack multiple
times for each of the ancestors of a keyword to see if there is a more generic handler as well.

## Sponsored by Guaranteed Rate

![Guaranteed Rate](https://mx-images.guaranteedrate.com/gr-2color.png)

