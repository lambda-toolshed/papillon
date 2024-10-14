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

Amongst other applications, we use Clojure(Script) on AWS Lambdas where a number of them are triggered asynchronously without a HTTP request.  The goal of this library is to advocate for the idea of interceptors, while providing a way to use them that are independent from HTTP and applicable in a broader range of scenarios.  We feel that interceptors have a compelling story when used in conjunction with inherently source-sink type operations -whether the source is an inbound http request or an inbound lambda event in AWS or even an outbound network request.

A side goal of this is to provide the core of the execution of an interceptor queue, while keeping it open enough that it can be composed with other complimentary libraries without locking the consumer into using specific libraries required for other aspects, such as picking a logging library.

### Goals

#### Clojure Common

As mentioned above, we run ClojureScript on Node runtime for our AWS Lambdas, so we needed a solution that covers both Clojure and ClojureScript.

In addition, we want papillon to be unencumbered with additional dependencies regardless of the host environment -be it Babashka, Clojure, ClojureScript, Clojure.NET, ClojurErl, ClojureDart, etc.  Since the introduction of the Chrysalis protocol papillon supports synchronous and asynchronous operation with no dependencies beyond the core language itself.

#### Interceptor focused

There are multiple libraries that include support for interceptors and even a few that focus on interceptors alone.  In keeping with our goal of broad platform support and zero external depedencies, papillon is an interceptor-only library.  We focus on keeping it slim and broadly applicable to many tasks.

#### Decouple the interceptors from HTTP requests.

Sieppari was more focused on Interceptors only, but was based on the idea of a Request/Response model.  Same for babashka's http client.

With our goal to have interceptors be the prevalent pattern in our AWS Lambdas, we needed something that would fit with both the HTTP style of synchronous AWS Lambdas, as well as the asynchronous AWS Lambdas that consume their items from SQS queues, the idea of contorting a SQS message into a HTTP Request/Response was something we wanted to avoid.  We have found papillon is well suited to many source-to-sink-to-source type operations: HTTP request handling and issuing HTTP requests are but two examples.

#### Minimal

We focused on what seemed to be the core of interceptors which is having an execution chain of interceptors to run, and running a context map through that chain and back out.

We have tried to leave out most everything else, as we found the interceptor chains can easily be modified to include many orthogonal concerns, allowing us to decomplect the interceptor execution from other useful but separate concerns.

One example of something we have left out is logging. While it is useful to log the path through the interceptor chain, and modifications to the execution chain, we didn’t want to pick a logging library that consuming applications would inherit our decision on.  It is, however,
possible to have papillon record a trace of all executed operations.

#### Data First

We also found that given the concept of the interceptor chain being just data, we could get logging (and various other concerns, e.g. benchmarking, tracing, etc.), included by manipulating the interceptor chain using interleave with a repeated sequence of a logging specific interceptor.

This ability to treat both the context as data and the control flow as data, allowed us to keep the core flow of domain logic as interceptors, distinct from logging and other developer related concerns, allowing us to highlight the core context.

Given that the control flow is data, and available on the context, it allowed us to play with ideas like setting up support for a Common Lisp style Condition System as seen in the examples folder.

#### Clojure Core Libraries Based

As noted above, papillon does not expose you to transitive dependencies.  The core of papillon has zero dependencies other than core Clojure language constructs.  Even async support is expressed with callbacks so as to limit the imposed requirement for something like clojure.core.async.  Nevertheless, papillon provides opt-in support for core.async and extending papillon to support other async libs (e.g. manifold or promesa or Java CompletableFuture) is a simple matter of extending the Chrysalis protocol's emerge function.

We also would love to see some more abuses of interceptors as well, because it helps find the edges of what can(not) or should (not) be done with them.

#### Async and Sync support
We want papillon to support synchronous and asynchronous use cases and we find different interpretations of what that could mean, roughly divided into two arenas.
1. Papillon allows interceptors to return deferred computations of the updated context.  An interceptor chain can contain a mix of interceptors that produce deferred results and interceptors that produce realized results; a given interceptor can even vary its return type conditionally.  We call an interceptor that returns a deferred type (more on that later) an "async interceptor."  By necessity, papillon always realizes the deferred result of an async interceptor before invoking the next interceptor in the chain.
2. Papillon allows the result of executing the entire interceptor chain to be deferred, invoking a user-supplied callback function upon completion.  We call this an "async chain."

These two approaches can be mixed and matched:

* Sync chain and sync interceptors: the baseline.  Appropriate for computation-focused chains or situations where the chain execution context has been managed by the developer prior to invocation.
* Sync chain and async interceptors: useful for testing async interceptors and for reusing (possibly) async interceptors transparently in sync chains.
* Async chain and sync interceptors: useful for reusing sync interceptors transparently in async chains.
* Async chain and async interceptors: essential for single-threaded environments like ClojureScript where otherwise blocking operations must yield to the event loop.

By using a callback, papillon does not impose an async solution on developers.  It works equally well with promises (in Clojure or ClojureScript), core.async channels (in Clojure and ClojureScript), futures, etc.

The Chrysalis protocol is central to papillon's async support.  It is used by papillon to realize the deferred result of an async interceptor.  Realizing a deferred value in a sync chain using the single-arity `emerge` function of Chrysalis, while realizing a deferered value in an async chain uses the two-arity version.  Here's an example extending support in Clojure and ClojureScript to core.async channels:

``` clojure
(extend-protocol Chrysalis
  #?(:clj  clojure.core.async.impl.protocols.Channel :cljs cljs.core.async.impl.channels/ManyToManyChannel)
  (emerge ([this] (async/<!! this))
          ([this callback] (async/take! this callback))))
```
Note how the single-arity version blocks while the two-arity does not.  All implementations of Chrysalis must observe this restriction!

Executing a chain asynchronously requires that you provide a callback function when invoking `papillon/execute`.  Here's a Clojure example using a promise to convey the result asynchronously to the caller:

``` clojure
(let [p (promise)]
  (ix/execute ixs ctx (fn [ctx] (deliver p ctx)))
  p)
```

## How it works

### Summary

If Either Monads are burritos, then Interceptor Chains are like Dagwood sandwiches for a hungry caterpillar.

The interceptor chain pattern is an execution pipeline that runs forward to the end of the chain, and then turns around and goes back out the pipeline in reverse order, allowing you to specify things like how to clean up resources on the way back out of the pipeline.

This is the hungry caterpillar idea.

The caterpillar starts at the top of the sandwich, and eats its way down through all the layers of the sandwich, some of which may be empty like the holes in some delicious Swiss cheese, or items only on one side of the sandwich; then when the caterpillar has exited the bottom, it chomps its way back up to the top on the other half of the sandwich resulting in a well fed caterpillar that is now a beautiful butterfly.

Sometimes things go wrong, and the caterpillar eats something that didn't agree with it making it sick.  In that case, the caterpillar scurries to the outside of the sandwich, and starts walking up the outside crust taking nibbles of things here and there until something settles its indigestion, at which point it goes back into the sandwich once again, and continues to eat, or otherwise climbing back up the outside of the sandwich to the top where you get a sick caterpillar waiting for you.

<img src="./papillon-sandwich.png" alt="Drawing of a caterpillar atop a giant stacked sandwich with ingredients that go across the whole sandwich, and some that are on half only." height="1200"/>

### The Spec

Interceptors are represented as a map with the optional keys of `:enter`, `:leave`, `:error` and `:final`.  None of the keys are required to be on an interceptor map; if no function value for the current stage is found in the interceptor map, the chain executor will move to the next interceptor.  A `:name` can also provided, in which case there are some affordances for tracing the interceptor execution by name.

The idea of sticking to a map instead of a record is that if the interceptor is a map, consumers can attach any other data to the interceptor, which the executor will ignored instead of actively discarding when converting to a record, allowing the extra keys and values on the interceptor map to be accessible while it exists on the queue or the stack in the context.

#### The IN phase
Execution of the chain starts in the IN phase.  During the IN phase, interceptors are removed from the queue and placed on the stack.  They are executed by invoking their `:enter` stage.

##### The :enter Stage
The `:enter` stage is the initial stage of the chain.  While in the `:enter` stage the chain is executed by invoking interceptors as they are removed the queue and placed on the stack.

There are three ways to transition from the `:enter` stage:
1. When the queue of remaining interceptors is empty, execution transitions to the `:leave` stage.
2. When the interceptor returns a `reduced?` value, the queue is cleared and execution transitions to the `:leave` stage.
3. When the interceptor throws (sync mode) or returns (async mode) an exception, execution transitions to the `:error` stage.

#### The OUT phase
During the OUT phase, interceptors are consumed from the stack after execution of their `:leave` and/or `:error` stages followed by their `:final` stage.  Invocation follows the `try...catch...finally` logic
of Clojure itself.  The possible stage orderings are thus as follows:

A. `:leave`-`:final`: baseline; the ordering in the absence of any errors.
B. `:leave`-`:error`-`:final`: when the `:leave` stage encounters an error.
C. `:error`-`:final`: when an unhandled error from an inner interceptor has "bubbled" up.

After the`:final` stage completes (regardless of outcome), the current interceptor is popped off the stack and execution continues.  When the stack is empty, the chain itself has been completely executed.

##### The :leave Stage
The `:leave` stage is executed from the interceptor at the top of the stack.  When the `:leave` stage of the interceptor throws (sync mode) or returns (async mode) an exception, chain execution transitions to the `:error` stage _starting with the current interceptor_.  If the `:leave` stage completes normally, the `:final` stage of the current interceptor is executed and the next interceptor from the stack is started in the `:leave` stage.

##### The :error Stage
The `:error` stage is executed from the interceptor at the top of the stack.   If the error at the `:lambda-toolshed.papillon/error` key is not cleared, the `:final` stage of the current interceptor is executed and the next interceptor from the stack is stared in the `:error` stage.  If it the error is cleared, the `:final` stage of the current interceptor is still executed, but the next interceptor from the stack is started in the `:leave` stage. 

##### The :final Stage
The `:final` stage is executed from the interceptor popped off the top of the stack.  The `:final` stage will be executed for every interceptor.  Idiomatically the `:final` stage should not alter the value at the `:lambda-toolshed.papillon/error` key of the context -behavior is undefined in this case.

##### Notes on Raising, or Returning, an Error

When an interceptor function throws or returns an error, e.g. ExceptionInfo, Throwable in Clojure, js/Error in Clojurescript, the queue, the error is added under the `:lambda-toolshed.papillon/error` key, and papillon begins processing the `:error` stage of the accumulated/remaining stack.  When papillon is processing the `:enter` stage of the queue, the accumulated stack will have the "current" interceptor at the head and exceptions will cause the `:error` stage of the same interceptor to be invoked.  However, when papillon is processing the `:leave` stage the current interceptor is removed from the stack prior to invoking the function and exceptions will thus advance to the next ("outward") interceptor.

The interceptor stack will continue to be consumed through the `:error` stage until the error is resolved by removing the `:lambda-toolshed.papillon/error` key from the context map.  Once there is no error in the context, processing will return to the `:leave` stage of the stack.

#### The Context map

##### Keys

| key                               | description                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------                            | --------------                                                                                                                                                                                                                                                                                                                                                                                                             |
| `:lambda-toolshed.papillon/queue` | The queue of interceptors.  This gets initialized with the interceptors passed to `execute`, but can be managed if you know what you are doing.                                                                                                                                                                                                                                                                            |
| `:lambda-toolshed.papillon/stack` | The stack of interceptors for traversing back through the chain of interceptors encountered.                                                                                                                                                                                                                                                                                                                               |
| `:lambda-toolshed.papillon/error` | This key should have the error information associated with it.  This key signifies we are in an error state, and interceptors with `:error` key will be processed, either for them to clean up some state (open connections, etc.) or attempt to handle and resolve the error and return nicely.  A few examples might be: turn the error into 500 HTTP response; put original message and error onto an error queue; etc. |
| `:lambda-toolshed.papillon/trace` | A vector at this key signals that interceptor chain execution should be traced by conj'ing tuples of the form `[itx-name stage]` onto the vector at every step. |

### Asynchronous Interceptors

Papillon always uses the `emerge` function of the Chrysalis protocol to realize deferred results *before* calling the next interceptor in the chain.  When the chain is being executed asynchronously (implicit when a callback function is provided to `execute`) and an interceptor returns a deferred value (a _chrysalis_), the two-arity `emerge` is used to asynchronously resolve the _chrysalis_ and resume the chain execution.  When the chain is being executed synchronously (implicit when no callback function is provided to `execute`) and an interceptor returns a chrysalis, the single arity `emerge` is used to synchronously resolve the chrysalis (likely blocking) and return it so that papillon can continue executing the chain.  Because synchronous chain execution with even one asynchronous interceptor requires blocking, this combination is inherently not possible _in ClojureScript_.

#### Errors in asynchronous interceptors

To signal an error in an asynchronous interceptor, the executor cannot just catch an exception, as the error occurs in a different timeline from where the code that started the process lives, so any catch for an exception has fallen out of scope.

Because of this, the Interceptor library expects the asynchronous interceptors to catch any errors themselves and return the error as its return value.

If the result of "emerging" the chrysalis (deferred result) yields an exception, papillon will add the error to the context under `:lambda-toolshed.papillon/error` key, as if it had been caught from a synchronous interceptor.

## Examples and Other ways to extend usage

### Interceptor Tricks

Because the interceptor executor takes a sequence of interceptors to build the processing queue, we can manipulate it before and even during execution.  In the example below, if we have tracing enabled, we interleave a tracing interceptor, could be a timing capture interceptor, with the standard interceptors we are expecting to process as part of the interceptor chain.

And example of this is found in [examples/example.cljc](./examples/example.cljc), and the `with-tracing` function that interleaves a tracing interceptor with a sequence of interceptors when in "debug" mode.

There is also an example of another tracing system that is a bit more advanced in [examples/dynamic_tracing.cljc](examples/dynamic_tracing.cljc), which wraps every following interceptor in the queue with tracing/timing functions, if the interceptor map is marked with meta-data.

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

A basic example of this can be found in [examples/condition_system.cljc](./examples/condition_system.cljc), along with and advanced condition system that uses derived keywords, and the derivation hierarchy of a keyword signal.
