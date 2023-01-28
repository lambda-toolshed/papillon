(ns lambda-toolshed.papillon-test
  (:require
   [clojure.core.async :as async :refer [alts! go]]
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.test-utils :refer [go-test runt! runt-fn!] :include-macros true]))

(def ^:private invocation-tracking-ctx
  {:enter-invocation-counts 0
   :leave-invocation-counts 0
   :error-invocation-counts 0})

(defn- track-invocation
  [stage ctx]
  (update-in ctx [(keyword (str (name stage) "-invocation-counts"))] inc))

(def ^:private track-error-ix
  {:error (partial track-invocation :error)})

(def ^:private track-error-async-ix
  {:error (fn [ctx]
            (go
              (track-invocation :error ctx)))})

(def ^:private track-enter-ix
  {:enter (partial track-invocation :enter)})

(def ^:private track-enter-async-ix
  {:enter (fn [ctx]
            (let [new-ctx (track-invocation :enter ctx)]
              (go new-ctx)))})

(def ^:private track-leave-ix
  {:leave (partial track-invocation :leave)})

(def ^:private track-leave-async-ix
  {:leave (fn [ctx]
            (go
              (track-invocation :leave ctx)))})

(def ^:private track-ix (merge track-enter-ix track-leave-ix track-error-ix))

(defn- throws-ix
  [stage ex]
  {stage (fn [_ctx] (throw ex))})

(def ^:private capture-ix
  {:error (fn [{error :lambda-toolshed.papillon/error :as ctx}] (-> ctx
                                                                    (dissoc :lambda-toolshed.papillon/error)
                                                                    (assoc ::error error)))})

(defn- async-returns-error-ix
  [stage ex]
  {stage (fn [_ctx] (go ex))})

(deftest enqueue
  (testing "enqueues interceptors to an empty context"
    (let [ixs [{:enter identity}]
          ctx (ix/enqueue {} ixs)]
      (is (:lambda-toolshed.papillon/queue ctx))
      (is (= (:lambda-toolshed.papillon/queue ctx) ixs))))
  (testing "enqueues interceptors to existing interceptors"
    (let [ixs [track-error-ix
               track-enter-ix
               track-leave-ix]
          ixs2 [{:enter identity}]
          ctx (ix/enqueue (ix/enqueue {} ixs) ixs2)]
      (is (:lambda-toolshed.papillon/queue ctx))
      (is (= (:lambda-toolshed.papillon/queue ctx) (apply conj ixs ixs2))))))

(deftest clear-queue
  (testing "removes the queue key from the context"
    (let [ixs [{:enter identity}]
          ctx (ix/clear-queue (ix/enqueue {} ixs))]
      (is (not (contains? ctx :lambda-toolshed.papillon/queue))))))

(def empty-context {:lambda-toolshed.papillon/queue #?(:clj clojure.lang.PersistentQueue/EMPTY
                                                       :cljs cljs.core/PersistentQueue.EMPTY)
                    :lambda-toolshed.papillon/stack []})

(defn ->async
  [itx]
  "Convert the synchronous interceptor `itx` to the async equivalent"
  (-> itx
      (update :enter #(when % (fn [ctx] (async/go (% ctx)))))
      (update :leave #(when % (fn [ctx] (async/go (% ctx)))))
      (update :error #(when % (fn [ctx] (async/go (% ctx)))))))

(deftest allows-for-empty-chain-of-interceptors
  (is (= empty-context (ix/execute {} []))))

(deftest allows-for-interceptor-chain-of-only-enters
  (let [ixs [track-enter-ix]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= 1 (:enter-invocation-counts res)))
    (is (= 0 (:leave-invocation-counts res)))
    (is (= 0 (:error-invocation-counts res))))
  (go-test
   (let [ixs [(->async track-enter-ix)]
         [res _] (async/alts! [(ix/execute invocation-tracking-ctx ixs)
                               (async/timeout 10)])]
     (is (map? res))
     (is (empty? (:lambda-toolshed.papillon/queue res)))
     (is (empty? (:lambda-toolshed.papillon/stack res)))
     (is (= 1 (:enter-invocation-counts res)))
     (is (= 0 (:leave-invocation-counts res)))
     (is (= 0 (:error-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-leaves
  (let [ixs [track-leave-ix]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= 0 (:enter-invocation-counts res)))
    (is (= 1 (:leave-invocation-counts res)))
    (is (= 0 (:error-invocation-counts res))))
  (go-test
   (let [ixs [(->async track-leave-ix)]
         [res _] (async/alts! [(ix/execute invocation-tracking-ctx ixs)
                               (async/timeout 10)])]
     (is (map? res))
     (is (empty? (:lambda-toolshed.papillon/queue res)))
     (is (empty? (:lambda-toolshed.papillon/stack res)))
     (is (= 0 (:enter-invocation-counts res)))
     (is (= 1 (:leave-invocation-counts res)))
     (is (= 0 (:error-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-errors
  (let [ixs [track-error-ix]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= 0 (:enter-invocation-counts res)))
    (is (= 0 (:leave-invocation-counts res)))
    (is (= 0 (:error-invocation-counts res))))
  (go-test
   "Nothing to be done; chain can't start async processing with only error fns"))

(deftest error-stage-is-never-invoked-if-no-error-has-been-thrown
  (let [ixs (repeat 2 track-ix)
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (map? res))
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= 0 (:error-invocation-counts res))))
  (go-test
   (let [ixs (repeat 2 (->async track-ix))
         [res _] (async/alts! [(ix/execute invocation-tracking-ctx ixs)
                               (async/timeout 10)])]
     (is (map? res))
     (is (empty? (:lambda-toolshed.papillon/queue res)))
     (is (empty? (:lambda-toolshed.papillon/stack res)))
     (is (= 0 (:error-invocation-counts res))))))

(deftest exception-semantics-are-preserved
  (let [the-exception (ex-info "the exception" {})
        ixs [(throws-ix :enter the-exception)]]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"the exception"
         (ix/execute invocation-tracking-ctx ixs))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async {:enter identity}) ; transition to async
              (throws-ix :enter the-exception)]
         [res _] (async/alts! [(ix/execute invocation-tracking-ctx ixs)
                               (async/timeout 10)])]
     (is (= the-exception res)))))

(deftest error-chain-is-invoked-when-enter-throws-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             track-error-ix
             track-error-ix
             (merge (throws-ix :enter the-exception)
                    track-error-ix)]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (= the-exception (::error res)))
    (is (= 3 (:error-invocation-counts res))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async capture-ix)
              (->async track-error-ix)
              (->async track-error-ix)
              (->async track-error-ix)
              (throws-ix :enter the-exception)]
         [res _] (alts! [(ix/execute invocation-tracking-ctx ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= the-exception (::error res)))
     (is (= 3 (:error-invocation-counts res))))))

(deftest error-chain-is-invoked-when-leave-throws-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             (merge (throws-ix :leave the-exception)
                    track-error-ix)]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (= the-exception (::error res)))
    (is (zero? (:error-invocation-counts res))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async capture-ix)
              (merge (throws-ix :leave the-exception)
                     track-error-ix)]
         [res _] (alts! [(ix/execute invocation-tracking-ctx ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= the-exception (::error res)))
     (is (zero? (:error-invocation-counts res))))))

(deftest leave-chain-is-resumed-when-error-processor-removes-error-key
  (let [the-exception (ex-info "the exception" {})
        ixs [(merge track-leave-ix track-error-ix)
             capture-ix
             (merge (throws-ix :leave the-exception)
                    track-error-ix)]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (not (contains? res :lambda-toolshed.papillon/error)))
    (is (zero? (:error-invocation-counts res)))
    (is (= 1 (:leave-invocation-counts res))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async (merge track-leave-ix track-error-ix))
              (->async capture-ix)
              (merge (throws-ix :leave the-exception)
                     track-error-ix)]
         [res _] (alts! [(ix/execute invocation-tracking-ctx ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (not (contains? res :lambda-toolshed.papillon/error)))
     (is (zero? (:error-invocation-counts res)))
     (is (= 1 (:leave-invocation-counts res))))))

(deftest reduced-context-stops-enter-chain-processing
  (let [ixs [(merge track-enter-ix track-leave-ix)
             {:enter reduced}
             track-enter-ix]
        res (ix/execute invocation-tracking-ctx ixs)]
    (is (= 1 (:enter-invocation-counts res)))
    (is (= 1 (:leave-invocation-counts res))))
  (go-test
   (let [ixs [(->async (merge track-enter-ix track-leave-ix))
              (->async {:enter reduced})
              (->async track-enter-ix)]
         [res _] (alts! [(ix/execute invocation-tracking-ctx ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= 1 (:enter-invocation-counts res)))
     (is (= 1 (:leave-invocation-counts res))))))

(do
  #?(:cljs
     (deftest allows-for-promise-return-values
       (go-test
        (let [ixs [{:enter (fn [ctx]
                             (js/Promise.resolve
                              (update-in ctx [(keyword (str (name :enter) "-invocation-counts"))] inc)))}]
              [res _] (alts! [(ix/execute invocation-tracking-ctx ixs)
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (:lambda-toolshed.papillon/queue res)))
          (is (empty? (:lambda-toolshed.papillon/stack res)))
          (is (= 1 (:enter-invocation-counts res)))
          (is (= 0 (:leave-invocation-counts res)))
          (is (= 0 (:error-invocation-counts res))))))))
