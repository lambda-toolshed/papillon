(ns lambda-toolshed.papillon.examples.example
  (:require
   [lambda-toolshed.papillon :as papillon :refer [enqueue execute]]
   [clojure.core.async :as async :refer [go <! >! chan]]
   clojure.pprint))

;; Synchronous interceptor that only handles items
;; on enter, and adds a new key to the context
(def one-ix
  {:name :one-ix
   :enter (fn [ctx]
            (assoc ctx :number 1))})

;; Run an interceptor chain with one interceptor in it that is synchronous
;; and does not take an initial context to augment
(let [c (execute [one-ix])]
  (clojure.pprint/pprint c))

;; Synchronous interceptor with that only handles items
;; on enter, and updates an existing key in the context
(def double-number-ix
  {:name :double-number-ix
   :enter (fn [ctx]
            (update ctx :number #(* % 2)))})

;; Run an interceptor chain with one interceptor in it that is synchronous
;; and does not take an initial context to augment
(let [c (execute [one-ix
                  double-number-ix])]
  (clojure.pprint/pprint c))

;; Define an interceptor that prints out a message for the different
;; stages that it handles, along with the context
(defn make-logger-ix [enter-msg leave-msg error-msg]
  {:name :logger-ix
   :enter (fn [ctx]
            (println "logger-ix" enter-msg)
            (clojure.pprint/pprint ctx)
            ctx)
   :leave (fn [ctx]
            (println "logger-ix" leave-msg)
            (clojure.pprint/pprint ctx)
            ctx)
   :error (fn [ctx]
            (println "logger-ix" error-msg)
            (clojure.pprint/pprint ctx)
            ctx)})

;; The execute takes a seq of interceptors for the queue, so
;; we can maniuplate the base sequence of interceptors before we
;; start execution
(let [c (execute (interleave (repeat (make-logger-ix "entering" "leaving" "errored ❌"))
                             [one-ix
                              double-number-ix
                              double-number-ix]))]
  (clojure.pprint/pprint c))

;; More complex debugging functionality, that shows that since the queue
;; and the stack are on the context, one can use that to their advantage
(letfn [(describe-interceptor [ix] (or (:name ix) ix))
        (prettify [ixs] (into (empty ixs) (map describe-interceptor) ixs))
        (prettify-keys [ctx & ks] (reduce (fn [accum k] (update accum k prettify)) ctx ks))
        (prettify-ctx [ctx] (prettify-keys ctx
                                           :lambda-toolshed.papillon/queue
                                           :lambda-toolshed.papillon/stack))
        (make-debugger [stage] (fn [ctx]
                                 (clojure.pprint/pprint (str "Debug:: stage" stage))
                                 (clojure.pprint/pprint (prettify-ctx ctx))
                                 (println)
                                 ctx))]
  (def debug-ix
    {:name :trace-ix
     :enter (make-debugger :enter)
     :leave (make-debugger :leave)
     :error (make-debugger :error)}))

;; Are we in debug mode?
;;   (of course we are; we are playing with the examples.)
;;   real code could pull from env/config/dynamic var/request header, etc.
(def debug true)

;; A simplistic debugger helper to conditionally enable itx debugging
(defn with-debugging
  [ixs]
  (if debug
    (interleave (repeat debug-ix) ixs)
    ixs))

;; Do some doubling, but use the pretty tracing to show how the
;; context is available to be munged for display, without updating
;; the context itself and killing the execution chain.
;; Persistant Data Structures FOR THE WIN!!
(let [c (execute (with-debugging [one-ix
                                  double-number-ix
                                  double-number-ix]))]
  (clojure.pprint/pprint c))

;; Asynchronous handler; returns a channel with the updated context inside it
;; Simple version where it is a go block
(def async-double-number-ix
  {:name :async-double-number-ix
   :enter (fn [ctx]
            (go (update ctx :number #(* % 2))))})

;; Do some asynchronous doubling, and use the pretty debugging to show how
;; the context is available to be munged for display, without updating
;; the context itself and killing the execution chain.
;; Persistant Data Structures FOR THE WIN!!
(go
  (let [c (execute (with-debugging [one-ix
                                    async-double-number-ix
                                    async-double-number-ix]))]
    (clojure.pprint/pprint (<! c))))

;; Asynchronous handler; returns a channel with the updated context inside it
;; Any channel will do
(def async-square-number-ix
  {:name :async-square-number-ix
   :enter (fn [ctx]
            (let [c (chan)]
              (go
                (>! c (update ctx :number #(* % %))))
              c))})

;; Do some asynchronous doubling and squaring, with pretty debugging
(go
  (let [c (execute (with-debugging [one-ix
                                    async-double-number-ix
                                    async-square-number-ix
                                    async-double-number-ix
                                    async-square-number-ix]))]
    (clojure.pprint/pprint (<! c))))

;; mark the context as reduced to stop processing
(def reduced-ix
  {:name :reduced-ix
   :enter reduced})

;; stop pretty much "immediately" after we get the number
;; in the context
(let [c (execute (with-debugging [one-ix
                                  reduced-ix
                                  async-double-number-ix
                                  async-square-number-ix
                                  async-double-number-ix
                                  async-square-number-ix]))]
  (clojure.pprint/pprint c))

;; "Something went wrong", this synchronous interceptor
;; throws an error
(def error-ix
  {:name :error-ix
   :enter (fn [_] (throw (ex-info "oh noes!!! ☠️ ☠️ ☠️" {})))})

;; we bail on errors, and start processing the stack calling
;; the error handler on the interceptors
(let [c (execute (interleave (repeat debug-ix)
                             [error-ix
                              async-double-number-ix
                              async-square-number-ix
                              async-double-number-ix
                              async-square-number-ix]))]
  (clojure.pprint/pprint c))

;; Error handlers that would like to resolve the error
(def resolving-error-handler-ix
  {:name :resolving-error-handler-ix
   :error (fn [ctx]
            (println "handling error; you can breath a sigh of relief")
            (dissoc ctx :lambda-toolshed.papillon/error))})

;; Error handlers have to be registered before the interceptor that throws/returns
;; the error since things after it never get called.
(let [c (execute (interleave (repeat debug-ix)
                             [resolving-error-handler-ix
                              error-ix]))]
  (clojure.pprint/pprint c))

;; Error handlers don't have to resolve, they may only clean up resources
;; created in :enter
(def resource-cleanup-error-handler-ix
  {:name :resource-cleanup-error-handler-ix
   :enter (fn [ctx]
            (println "Opening DB Connection")
            (assoc ctx :db-connection :chewing-up-a-thread-pool-resource))
   :error (fn [ctx]
            (println "Don't know how to handle the error, but have to clean up after myself 🧹 🗑️")
            (dissoc ctx :db-connection))})

;; sometimes you want to do a "finally" style of clean up when working through the stack
;; that gets invoked on both the :leave and :error chain
;; a let over a def can help with that...
(letfn [(cleanup [ctx]
          (println "🎵Clean Up, Pick Up, Put Away. 🎵 🐯🧹🗑️")
          (dissoc ctx :db-connection))]
  (def finally-style-cleanup-error-handler-ix
    {:name :finally-style-cleanup-error-handler-ix
     :enter (fn [ctx]
              (println "Opening DB Connection")
              (assoc ctx :db-connection :chewing-up-a-thread-pool-resource))
     :leave cleanup
     :error cleanup}))

;; Error handlers don't have to resolve, they may transform the result, or
;; take some other action if needed
(def transforming-error-handler-ix
  {:name :transforming-error-handler-ix
   :error (fn [ctx]
            (println "Don't directly handle the error, but transform the context or do something else")
            (println "It might turn the response to a 500 error if HTTP, add to error queue, etc.")
            (update ctx :number (fn [n] (if (= 1 n) "one" str))))})

;; Error handlers are allowed to not handle the error, but do other processing
(let [c (execute (interleave (repeat debug-ix)
                             [one-ix
                              resource-cleanup-error-handler-ix
                              transforming-error-handler-ix
                              error-ix]))]
  (clojure.pprint/pprint c))

;; "Something went wrong", asynchronous interceptors return an error
;; since throwing in async mode loses the thrown error
(def error-async-ix
  {:name :error-async-ix
   :enter (fn [_] (go (ex-info "oh noes!!! ☠️ ☠️ ☠️" {})))})

;; error handlers work the same if the error is returned inside the channel
;; If an error is detected it gets added to the context passed to the
;; interceptor that resulted in the error
(go (let [c (execute (interleave (repeat debug-ix)
                                 [one-ix
                                  resource-cleanup-error-handler-ix
                                  transforming-error-handler-ix
                                  error-async-ix]))]
      (clojure.pprint/pprint (<! c))))

;; Error handlers can be asynchronous as well...
(def async-transforming-error-handler-ix
  {:name :async-transforming-error-handler-ix
   :enter (fn [ctx]
            (println "Opening DB Connection")
            (assoc ctx :db-connection :chewing-up-a-thread-pool-resource))
   :error (fn [ctx]
            (println "Don't directly handle the error, but transform the context or do something else")
            (println "It might turn the response to a 500 error if HTTP, add to error queue, etc.")
            (go (update ctx :number (fn [n] (if (= 1 n) "one" (str n))))))})

;; the chain works the same regardless if the error handler is sync or async
(let [c (execute (interleave (repeat debug-ix)
                             [one-ix
                              resource-cleanup-error-handler-ix
                              async-transforming-error-handler-ix
                              error-async-ix]))]
  (clojure.pprint/pprint c))

;; a leave handler may also throw, and that starts running the error chain
(def leave-throws-ix
  {:name :leave-throws-ix
   :leave (fn [_] (throw (ex-info "oh snap!!!" {})))})

;; the error handling chain works the same regardless if the error
;; handler is sync or async
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [one-ix
                                resource-cleanup-error-handler-ix
                                async-transforming-error-handler-ix
                                leave-throws-ix
                                double-number-ix
                                async-square-number-ix
                                double-number-ix
                                async-square-number-ix]))]
    (clojure.pprint/pprint (<! c))))

;; a leave handler may also throw, and that starts running the error chain
(def async-leave-returns-error-ix
  {:leave (fn [_] (go (ex-info "oh snap!!!" {})))})

;; the error handling chain works the same regardless if the error
;; handler is sync or async
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [one-ix
                                resource-cleanup-error-handler-ix
                                async-transforming-error-handler-ix
                                async-leave-returns-error-ix
                                double-number-ix
                                async-square-number-ix
                                double-number-ix
                                async-square-number-ix]))]
    (clojure.pprint/pprint (<! c))))

;; an error handler may also throw, and that error superceeds the
;; previous error, so be careful
(def async-error-returns-error-ix
  {:error (fn [_] (go (ex-info "aww maannn!!! 🦊" {})))})

;; the error original error is swallowed
;; be careful about how your error handlers behave
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [one-ix
                                resource-cleanup-error-handler-ix
                                async-transforming-error-handler-ix
                                async-error-returns-error-ix
                                error-async-ix
                                double-number-ix
                                async-square-number-ix
                                double-number-ix
                                async-square-number-ix]))]
    (clojure.pprint/pprint (<! c))))

;; Handler functions may conditionally reduce the result
(def done-when-even-ix
  {:name :done-when-even-ix
   :enter (fn [ctx]
            (if (even? (:number ctx))
              (reduced ctx)
              ctx))})

;; exits early when the starting number is 2
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [async-square-number-ix
                                done-when-even-ix
                                double-number-ix])
                   {:number 2})]
    (clojure.pprint/pprint (<! c))))

;; continues through the whole chain when the starting number is 3
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [async-square-number-ix
                                done-when-even-ix
                                double-number-ix])
                   {:number 3})]
    (clojure.pprint/pprint (<! c))))

;; Interceptors can also manipulate the queue
;; this one uses `enqueue` to add more things to do
(def ensure-even-ix
  {:name :ensure-even-ix
   :enter (fn [ctx]
            (if (even? (:number ctx))
              ctx
              (enqueue ctx [double-number-ix async-square-number-ix async-square-number-ix])))})

;; continues through the whole chain when the starting number is 3
;; note the traceing doesn't happen here as the interleaving was
;; only on the first part of the queue that was created originally
;; if tracing is still desired, it would be part of the client
;; code that enqueues more items to the context
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [async-square-number-ix
                                ensure-even-ix])
                   {:number 3})]
    (clojure.pprint/pprint (<! c))))

;; the sqaure of 2 is even, so we are done; nothing more to add
;; to the queue
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [async-square-number-ix
                                ensure-even-ix])
                   {:number 2})]
    (clojure.pprint/pprint (<! c))))

;; Interceptors can also manipulate the queue
;; this one uses `enqueue` to add more things to do
(def ensure-even-with-tracing-ix
  {:name :ensure-even-with-tracing-ix
   :enter (fn [ctx]
            (if (even? (:number ctx))
              ctx
              (enqueue ctx (with-tracing [double-number-ix async-square-number-ix async-square-number-ix]))))})

;; continues through the whole chain when the starting number is 3
;; note the traceing doesn't happen here as the interleaving was
;; only on the first part of the queue that was created originally
;; if tracing is still desired, it would be part of the client
;; code that enqueues more items to the context
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [async-square-number-ix
                                ensure-even-with-tracing-ix])
                   {:number 3})]
    (clojure.pprint/pprint (<! c))))

;; the sqaure of 2 is even, so we are done; nothing more to add
;; to the queue
(go
  (let [c (execute (interleave (repeat debug-ix)
                               [async-square-number-ix
                                ensure-even-with-tracing-ix])
                   {:number 2})]
    (clojure.pprint/pprint (<! c))))

;; It can be tricky trying to handle both sync *and* async behavior from chain execution.  If you
;; are using *any* async interceptor fns it can be useful to force async returns and homogenize
;; the return semantics.
(let [force-async-itx {:leave (fn [ctx] (go ctx))
                       :error (fn [ctx] (go ctx))}
      maybe-async {:enter (fn [ctx] (if (even? (:number ctx)) (go ctx) ctx))}]
  (go (let [c (execute [force-async-itx maybe-async double-number-ix]
                       {:number 1})]
        (clojure.pprint/pprint (<! c))))
  (go (let [c (execute [force-async-itx double-number-ix maybe-async]
                       {:number 2})]
        (clojure.pprint/pprint (<! c)))))
