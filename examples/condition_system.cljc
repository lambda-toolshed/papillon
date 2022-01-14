(require '[lambda-toolshed.papillon :as papillon :refer [enqueue execute into-queue]])
(require '[clojure.core.async :as async :refer [go <! >! chan]])
(require 'clojure.pprint)

(defn simple-signal
  "Walks up the interceptor stack looking for an interceptor
  with a key on it that matches the keyword passed in as
  the signal.

  Takes the signal keyword to look for, the context, and
  extra args to be passed to the signal handler.

  If no interceptor is found with the keyword in its map
  and a corresponding function, this will throw an error."
  [sig ctx & args]
  (loop [[ix & ix-stack] (:lambda-toolshed.papillon/stack ctx)]
    (if ix
      (if-let [f (sig ix)]
        (apply f (concat [ctx] args))
        (recur ix-stack))
      (throw (ex-info "no signal handler found"
                      {:signal sig
                       :ctx ctx
                       :args args})))))

(defn requeue-current
  "Helper method to add the current interceptor being processed
  (the top of the stack) to the queue as the immediate next
  interceptor to process, and removes the current interceptor
  from the stack to avoid dual attempts at cleanup."
  [ctx]
  (let [stack (:lambda-toolshed.papillon/stack ctx)
        current-as-queue (into-queue [(peek stack)])
        new-stack (pop stack)]
    (-> ctx
        (update-in [:lambda-toolshed.papillon/queue] #(into-queue current-as-queue %))
        (assoc :lambda-toolshed.papillon/stack new-stack))))

;; basic interceptor for examples
(def one-ix
  {:name :one-ix
   :enter (fn [ctx] (assoc ctx :number 1))})

;; basic interceptor for examples
(def double-ix
  {:name :double-ix
   :enter (fn [ctx] (update-in ctx [:number] (partial * 2)))})

;; make sure that requeue current looks good at a simple version
(-> {:lambda-toolshed.papillon/queue (into-queue [double-ix])
     :lambda-toolshed.papillon/stack [one-ix]}
    requeue-current)

;; make a (fake) http request
;; Not making real HTTP so we don't need a HTTP library for
;; examples, and one that will work nicely in a CLJC file too.
;;  - Our fake HTTP request occassionally returns non success
;;    status response
(defn http-request!
  "fakes out a http request to show examples.  Occasionally fails"
  [{:keys [url]}]
  (go
    {:url url
     ;; Random status with roughly 1/4 chance of failure
     :status (rand-nth [200 200 200 500])}))

;; Was the http response a success response??
(defn success?
  "Success if the response map status was in the 200 range"
  [response]
  ((set (range 200 300)) (:status response)))

(defn retry-request
  "This handler will retry a request until it hits the retry limit.

  It uses a sub-map on the context with the url as the key that tracks
  how many times a given url fails, so individual requests (keyed by url)
  can be tracked and retried individually.

  If the retry count is within the max retry count limit, this
  handler will update the retry count in the context for the request
  and requeue the current handler to try the response again.

  If the retry count limit has been exceeded, throws an exception
  signaling that we tried too many times and still failed"
  [max-retry-count ctx request response]
  (println "in retry-request")
  (let [url (:url request)
        retry-count (or (get-in ctx [:lambda-toolshed.papillon.examples.condition-system/request-retries url]) 0)]
    (println "retry-count..." retry-count)
    (if (>= retry-count max-retry-count)
      (throw (ex-info "Too Many Failures"
                      {:retry-count retry-count
                       :request-url url
                       :response response
                       :ctx ctx}))
      (do
        ;; timeout to do exponential back off
        (println "Fake timeout for" (* ((fnil inc 0) retry-count) 10) "seconds")
        (-> ctx
            (update-in [:lambda-toolshed.papillon.examples.condition-system/request-retries url] (fnil inc 0))
            requeue-current)))))

;; The Retry handler interceptor.
;; Notice that there is no :enter, :leave, or :error key
;; but a special key that is used to specify a handler
;; for a given signal, in this case :http-request-failed
;;  - Partially applies retry-request to configure it
;;    to only retry with a limit of 3 retries
(def retry-http-ix
  {:http-request-failed (partial retry-request 3)})

;; Tries to make a request and signals if the response was
;; not successful.  Async style, so need to catch exception in
;; the interceptor and return error on the channel result
(def request-ix
  {:enter (fn [ctx]
            (go
              (try
                ;; fake http request
                (let [request {:url (:request-url ctx) :headers []}
                      response (<! (http-request! request))]
                  (println "response... " response)
                  (if (not (success? response))
                    ;; signal if the response was not a success
                    (simple-signal :http-request-failed ctx request response) ;; look for :http-request-failed in an interceptor on the stack
                    ;; else we got a good response, add to context and continue
                    (assoc ctx :response response)))
                ;; This is async (in a go block), so we have to catch
                ;; the error and return the error as the value on
                ;; the channel as the result so it can be picked up
                ;; by the executor as having errored
                (catch #?(:clj Exception :cljs :default) e
                  e))))
   :leave (fn [ctx]
            (println "Even If I am re-queued from the retry-handler I only get run once.")
            ctx)})

;; Adds the retry-request handler interceptor first, and
;; then the request making interceptor
;;
;; Note: Might work first time because random success failures
;; run this repeatedly to watch for some errors happen
;;   - if you are running this locally, feel free to play
;;     with the sequence in the http-request! method to
;;     see different error rates
(go
  (let [c (execute {:request-url "https://www.example.com"}
                   [retry-http-ix
                    request-ix])]
    (clojure.pprint/pprint (<! c))))

;; Let's derive some keywords...
;; 🤔 🤔 🤔
;; where is this going with condition systems
;; and signal handlers
;; ❓❓❓
(derive ::http-request-failed ::retry-request)

(derive ::quadruped ::animal)
(derive ::dog ::mammal)
(derive ::dog ::quadruped)
(derive ::sporting_breed ::dog)
(derive ::beagle ::sporting_breed)
(derive ::flying-ace ::beagle)
(derive ::flying-ace ::pilot)

;; Lets build a depth first sequence of the keyword hierarchy
;; Practically we might want a breath first, but this is a
;; simple example for illustrative purposes
(defn derivation-heirarchy
  [kw]
  (lazy-seq (cons kw (mapcat derivation-heirarchy (parents kw)))))

;; Just showing the derivation hierarchy at work
(derivation-heirarchy ::flying-ace)
(derivation-heirarchy ::dog)
(derivation-heirarchy ::beagle)

;; Now we look at an advanced signaling mechanism.
(defn advanced-signal
  "Checks the signal in the stack of interceptors and
  invokes the handler with the context and args if
  a handler is found on the stack.

  If no handler is found for the keyword signal this
  was called with, it will start walking the keyword
  derivation hierarchy, and for each keyword in the
  derivation hierarchy it will re-process the stack
  looking for any handlers that can handle the
  current ancestor keyword until all ancestor keywords
  from the derivation heirarchy have been checked.

  If no handler for the signal, or any of the keywords
  the signal keyword is a descendant of, is found in
  the interceptor stack throw an error"
  [signal ctx & args]
  ;; get signal derivation hierarcy and stack
  (let [signal-hierarchy (derivation-heirarchy signal)
        stack (:lambda-toolshed.papillon/stack ctx)]
    (loop [[ix & ix-stack] stack
           [sig & sigs :as all-sigs] signal-hierarchy]
      (println "trying to find " sig " on interceptor " ix)
      (if-let [sig (first sigs)]
        (if ix ; do we have an interceptor? or did we exhaust the stack?  
          ;; yes we have an interceptor to check
          (if-let [f (sig ix)] ; can this interceptor handle the signal
            (apply f (concat [ctx] args)) ; yes? invoke it
            (recur ix-stack all-sigs)) ; no? try the next interceptor on the stack
          ;; we exhausted the stack
          (recur stack (rest sigs)) ; start the stack over with the rest of the signals
          )
        ;; no signal, we exhausted the signal hierarchy and found nothing
        (throw (ex-info "no signal handler found or found for any ancestors"
                        {:signal signal
                         :derivation-heirarchy signal-hierarchy
                         :ctx ctx
                         :args args}))))))

;; Does the same as request-ix; but calls advanced-signal
;; when the response is not a success
(def advanced-request-signal-ix
  {:enter (fn [ctx]
            (go
              (try
                (let [request {:url (:request-url ctx) :headers []}
                      response (<! (http-request! request))]
                  (println "response... " response)
                  (if (not (success? response))
                    (advanced-signal ::http-request-failed ctx request response) ;; look for :http-request-failed in an interceptor on the stack
                    (assoc ctx :response response)))
                (catch #?(:clj Exception :cljs :default) e
                  e))))})

;; Interceptor for handling retry-requests "generically"
;; by hooking into the `::retry-request` keyword
(def retry-request-ix
  {::retry-request (partial retry-request 3)})

;; remember we had ::http-request-failed derive
;; from ::retry-request
(derivation-heirarchy ::http-request-failed)

;; Run this many times and you should see some errors
;; you might see the error the first time if lucky
;;
;; Notice that now you see it tries to find the
;; ::http-request-failed key on the stack first
;; and then processes the stack looking for ::retry-request
(go
  (let [c (execute {:request-url "https://www.example.com"}
                   [retry-request-ix
                    advanced-request-signal-ix])]
    (clojure.pprint/pprint (<! c))))
