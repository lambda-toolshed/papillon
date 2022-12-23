(ns lambda-toolshed.papillon.examples.distributed-transaction
  (:require
   [lambda-toolshed.papillon :as papillon :refer [into-queue]]
   [clojure.core.async :as async :refer [go put! <! >! chan]]
   [clojure.pprint :as pp]))

(defn- expt [n power]
  (reduce * 1 (take (inc power) (repeat n))))

;; Lets build a depth first sequence of the keyword hierarchy
;; Practically we might want a breath first, but this is a
;; simple example for illustrative purposes
(defn- derivation-heirarchy
  "Builds a depth first sequence of the keyword hierarchy."
  [kw]
  (lazy-seq (cons kw (mapcat derivation-heirarchy (parents kw)))))

(defn signal
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
  [signal c-out ctx & args]
  ;; get signal derivation hierarcy and stack
  (let [signal-hierarchy (derivation-heirarchy signal)
        stack (:lambda-toolshed.papillon/stack ctx)]
    (loop [[ix & ix-stack] stack
           [sig & sigs] signal-hierarchy]
      (if sig
        (if ix ; do we have an interceptor? or did we exhaust the stack?  
          ;; yes we have an interceptor to check
          (if-let [f (sig ix)] ; can this interceptor handle the signal
            (apply f (concat [c-out ctx] args)) ; yes? invoke it
            (recur ix-stack signal-hierarchy)) ; no? try the next interceptor on the stack
          ;; we exhausted the stack
          (recur stack sigs) ; start the stack over with the rest of the signals
          )
        ;; no signal, we exhausted the signal hierarchy and found nothing
        (put! c-out (ex-info "no signal handler found or found for any ancestors"
                             {:signal signal
                              :derivation-heirarchy signal-hierarchy
                              :ctx ctx
                              :args args}))))))

(defn- requeue-current
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

(defn- retry!
  "This handler will retry a request until it hits the retry limit.
  It uses a sub-map on the context with the url as the key that tracks
  how many times a given url fails, so individual requests (keyed by url)
  can be tracked and retried individually.
  If the retry count is within the max retry count limit, this
  handler will update the retry count in the context for the request
  and requeue the current handler to try the response again.
  If the retry count limit has been exceeded, throws an exception
  signaling that we tried too many times and still failed"
  [base-opts c-out ctx {:keys [retry-key] :as retry-ctx}]
  (let [{:keys [max-retry-count back-off!] :as retry-opts} (merge base-opts retry-ctx)
        retry-count (or (get-in ctx [::retry-count retry-key]) 0)]
    (go
      (>! c-out
          (if (> retry-count (dec max-retry-count))
            (ex-info "Too Many Failures"
                     {:retry-count retry-count
                      :info retry-ctx})
            (do
              (<! (back-off! retry-count retry-opts))
              (-> ctx
                  (assoc-in [::retry-count retry-key] (inc retry-count))
                  requeue-current)))))))

(defn exponential-back-off!
  "Does an exponential backoff waiting for the number of milliseconds resulting
  from raising `initial-backoff-ms` to the power of `retry-count`."
  [retry-count {:keys [initial-backoff-ms] :as _retry-opts}]
  (async/timeout (expt initial-backoff-ms retry-count)))

(defn linear-back-off!
  "Does an linear backoff waiting for the number of milliseconds resulting from
  `initial-backoff-ms` multiplied by `retry-count`."
  [retry-count {:keys [initial-backoff-ms] :as _retry-opts}]
  (async/timeout (* initial-backoff-ms retry-count)))

(defn retry-ix
  "The Retry handler interceptor.
  Notice that there is no :enter, :leave, or :error key
  but a special key that is used to specify a handler
  for a given signal of `:retry`.

  This will retry `max-retry-count` before failing.

  Default retry strategy is Exponential Backoff with a starting
  time of 5 milliseconds"
  [opts]
  {:retry (partial retry! (merge {:max-retry-count 3
                                  :initial-backoff-ms 5
                                  :back-off! exponential-back-off!}
                                 opts))})

(def mark-transaction-unreconciled-ix
  {:name :mark-transaction-unreconciled-ix
   ;; Marks the transaction as reconciled in the database
   :enter (fn [{:example.ditributed-transactions/keys [mark-reconciled! transaction-id] :as ctx}]
            (println
             (pp/cl-format nil "marking record with id ~d in as reconciled~%" transaction-id))
            (go
              (try
                (mark-reconciled! transaction-id)
                ctx
                (catch #?(:clj Throwable :cljs :default)  err
                  err))))
   ;; basic logging that we successfully marked the transaction as reconciled since we
   ;; completed both transactions of marking the item in the database and publishing a
   ;; notification to reconcile that transaction
   :leave (fn [{:example.ditributed-transactions/keys [transaction-id] :as ctx}]
            (go
              (println
               (pp/cl-format nil "previously unreconciled transaction record with id of ~d was marked and notified~%" transaction-id))
              (assoc ctx :example.ditributed-transactions/reconcile-result :success)))
   #_"If we have an error, 'rollback' the transaction by marking it as unreconciled.  We either got here
   through the marking the transaction as reconciled, so we try to undo it, or we failed publishing
   the notification, so we need to retry the reconciler process to send a new notification to
   reconcile the transaction.

   If this happened and we rolledback successfully, we remove the error as we put the system back
   into a state that we can try again next reconcile run."
   :error (fn
            [{:example.ditributed-transactions/keys [mark-unreconciled! transaction-id]
              :lambda-toolshed.papillon/keys [error] :as ctx}]
            (println
             (pp/cl-format nil "encountered error marking transaction record ~d as unreconciled; rolling back orphan marking in the database; error was ~A~%" transaction-id error))
            (go
              (try
                (mark-unreconciled! transaction-id)
                ;; Mark the error as handled, as unmarking the transaction as unreconciled
                ;; means we will "ignore" the failure and catch it on the next round
                ;; of unreconciled transaction checking
                (dissoc ctx :lambda-toolshed.papillon/error)
                (catch #?(:clj Throwable :cljs :default) err
                  err))))})

(def build-transaction-sqs-message-ix
  {:name :transaction-sqs-message-ix
   :enter (fn [{:example.ditributed-transactions/keys [transaction-id] :as ctx}]
            (let [message {:transaction-id transaction-id}]
              (println
               (pp/cl-format nil "building message for unreconciled tranasction record with id: ~d~%" transaction-id))
              (assoc ctx :example.ditributed-transactions/retry-transaction-msg message)))})

(def notify-unreconciled-transaction-found-ix
  {:name :notify-unreconciled-transaction-found-ix
   :enter (fn [{:example.ditributed-transactions/keys [send-sqs-message! retry-transaction-msg transaction-id] :as ctx}]
            (go
              (try
                (assoc ctx :example.ditributed-transactions/sqs-message-result (send-sqs-message! retry-transaction-msg))
                ;; catch an error and signal a retry
                (catch #?(:clj Throwable :cljs :default) err
                  (let [c (chan 1)
                        ;; retry key is includes the transction-id to avoid collisions
                        ;; as well as tracking the error we caught to propagate if we fail the retry limit
                        retry-ctx {:retry-key (keyword (str "unreconcile-notify-transaction-" transaction-id))
                                   :error err}]
                    (signal :retry c ctx retry-ctx)
                    (<! c))))))})

(defn process-unreconciled-transaction-ixs []
  [(retry-ix {}) ;; We want to support retries if we signal such
   mark-transaction-unreconciled-ix
   build-transaction-sqs-message-ix
   notify-unreconciled-transaction-found-ix])

(defn process-unreconciled-transaction
  [parent-ctx
   {:keys [id] :as transaction-record}]
  (let [ctx (-> parent-ctx
                (select-keys [:example.ditributed-transactions/mark-reconciled!
                              :example.ditributed-transactions/mark-unreconciled!
                              :example.ditributed-transactions/send-sqs-message!])
                (assoc :example.ditributed-transactions/transaction-record transaction-record
                       :example.ditributed-transactions/transaction-id id))]
    (papillon/execute ctx (process-unreconciled-transaction-ixs))))

;; This also shows of spinning up multiple interceptor chains and executing
;; them in parallel and collecting the results into a single context result
;; for example, taking an interceptor chain at the outer and doing a set of
;; nested interceptors, e.g. a fork-join for updating individual transactions
(defn process-unreconciled-transactions [{:example.ditributed-transactions/keys [unreconciled-transaction-records] :as ctx}]
  (go
    (println
     (pp/cl-format nil "process-unreconciled-transactions~%"))
    (let [c (->> unreconciled-transaction-records
                 ;; run interceptors in parallel
                 (map (partial process-unreconciled-transaction ctx))
                 ;; merge channel results into single channel
                 (async/merge)
                 ;; turn channel of results into channel of vector of results; e.g. await-all
                 (async/reduce conj []))
          results (<! c)]
      ;; see if any items failed
      (if (some :lambda-toolshed.papillon/error results)
        (ex-info "process-unreconciled-transactions failed" {:results results})
        (assoc ctx :example.ditributed-transactions/process-unreconciled-transactions-results results)))))

;; Marking the item as reconciled
(defn mark-reconciled--always-succeeds [transaction-id]
  (println
   (pp/cl-format nil "marked transaction ~d as reconciled~%" transaction-id))
  :success)

;; Marking the item as unreconciled
(defn mark-unreconciled--always-succeeds [transaction-id]
  (println
   (pp/cl-format nil "marked transaction ~d as unreconciled~%" transaction-id))
  :success)

(defn occassionally-failing-sqs-send
  "creates a function that will have a success:failure ratio"
  [success-count failure-count]
  (let [success-chances (concat (repeat success-count true)
                                (repeat failure-count false))]
    (fn [sqs-message]
      (if (rand-nth success-chances)
        (do
          (println
           (pp/cl-format nil "successfully sent sqs message ~A~%" sqs-message))
          {:sqs-message-sending-result :success})
        (do
          (println
           (pp/cl-format nil "failed to sen sqs message ~A~%" sqs-message))
          (throw (ex-info "SQS message sending failed" {:success-count success-count
                                                        :failure-count failure-count})))))))

(go
  (let [send-sqs-message (occassionally-failing-sqs-send 2 1) ; success:failure ratio of 2:1
        ctx {:example.ditributed-transactions/unreconciled-transaction-records [{:id 356}
                                                                                {:id 874}
                                                                                {:id 291}]
             :example.ditributed-transactions/mark-reconciled! mark-reconciled--always-succeeds
             :example.ditributed-transactions/mark-unreconciled! mark-unreconciled--always-succeeds
             :example.ditributed-transactions/send-sqs-message! send-sqs-message}
        result (<! (process-unreconciled-transactions ctx))]
    (pp/pprint result)))
