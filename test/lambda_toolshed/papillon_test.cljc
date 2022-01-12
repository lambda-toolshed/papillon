(ns lambda-toolshed.papillon-test
  (:require
   [clojure.core.async :as async :refer [alts! go]]
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.test-utils :refer [go-test] :include-macros true]))

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

(defn- throws-ix
  [stage ex]
  {stage (fn [_ctx] (throw ex))})

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

(deftest allows-for-empty-chain-of-interceptors
  (go-test
    (let [c (ix/execute {} [])
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res))))))

(deftest allows-for-interceptor-chain-of-only-enters
  (go-test
    (let [ixs [track-enter-ix]
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 1 (:enter-invocation-counts res)))
      (is (= 0 (:leave-invocation-counts res)))
      (is (= 0 (:error-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-leaves
  (go-test
    (let [ixs [track-leave-ix]
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 0 (:enter-invocation-counts res)))
      (is (= 1 (:leave-invocation-counts res)))
      (is (= 0 (:error-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-errors
  (go-test
    (let [ixs [track-error-ix]
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 0 (:enter-invocation-counts res)))
      (is (= 0 (:leave-invocation-counts res)))
      (is (= 0 (:error-invocation-counts res))))))

(deftest error-stage-is-never-invoked-if-no-error-has-been-thrown
  (go-test
    (let [ixs (repeat 2 (merge track-enter-ix
                               track-error-ix))
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 0 (:error-invocation-counts res))))))

(deftest error-chain-is-invoked-when-enter-throws-an-exception
  (go-test
    (let [the-exception (ex-info "the exception" {})
          ixs [track-error-ix
               track-error-ix
               (merge (throws-ix :enter the-exception)
                      track-error-ix)]
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (= the-exception (:lambda-toolshed.papillon/error res)))
      (is (= 3 (:error-invocation-counts res))))))

(deftest error-chain-is-invoked-when-leave-throws-an-exception
  (go-test
    (let [the-exception (ex-info "the exception" {})
          ixs [track-error-ix
               track-error-ix
               (merge (throws-ix :leave the-exception)
                      track-error-ix)]
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (= the-exception (:lambda-toolshed.papillon/error res)))
      (is (= 2 (:error-invocation-counts res))))))

(deftest leave-chain-is-reinvoked-when-an-error-processor-removed-the-error-key
  (go-test
    (let [the-exception (ex-info "the exception" {})
          ixs [track-leave-ix
               track-leave-ix
               {:error (fn [ctx]
                         (-> ctx
                             (#(track-invocation :error %))
                             (dissoc :lambda-toolshed.papillon/error)))}
               (merge (throws-ix :leave the-exception)
                      track-error-ix)]
          c (ix/execute invocation-tracking-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (not (contains? res :lambda-toolshed.papillon/error)))
      (is (= 1 (:error-invocation-counts res)))
      (is (= 2 (:leave-invocation-counts res))))))

(deftest reduced-context-stops-enter-chain-processing
  (go-test
    (let [post-reduced-ix-calls (atom [])
          starting-ctx {:enter-invocation-counts 0
                        :leave-invocation-counts 0
                        :error-invocation-counts 0}
          ixs [(merge track-enter-ix
                      track-leave-ix)
               (merge {:enter reduced}
                      track-leave-ix)
               {:enter (fn [ctx] (swap! post-reduced-ix-calls conj ctx))
                :leave (fn [_] (throw (ex-info "should not process leaves of interceptors in the queue after a reduced value" {})))}]
          c (ix/execute starting-ctx ixs)
          [res p] (alts! [c (async/timeout 10)])]
      (is (= p c))
      (is (= [] @post-reduced-ix-calls))
      (is (= 1 (:enter-invocation-counts res)))
      (is (= 2 (:leave-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-async-enters
  (go-test
    (let [ixs [track-enter-async-ix]
          c (ix/execute invocation-tracking-ctx ixs)
          timeout (async/timeout 100)
          [res p] (alts! [c timeout])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 1 (:enter-invocation-counts res)))
      (is (= 0 (:leave-invocation-counts res)))
      (is (= 0 (:error-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-async-leaves
  (go-test
    (let [ixs [track-leave-async-ix]
          c (ix/execute invocation-tracking-ctx ixs)
          timeout (async/timeout 100)
          [res p] (alts! [c timeout])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 0 (:enter-invocation-counts res)))
      (is (= 1 (:leave-invocation-counts res)))
      (is (= 0 (:error-invocation-counts res))))))

(deftest allows-for-interceptor-chain-of-only-aysnc-errors
  (go-test
    (let [ixs [track-error-async-ix]
          c (ix/execute invocation-tracking-ctx ixs)
          timeout (async/timeout 100)
          [res p] (alts! [c timeout])]
      (is (= p c))
      (is (empty? (:lambda-toolshed.papillon/queue res)))
      (is (empty? (:lambda-toolshed.papillon/stack res)))
      (is (= 0 (:enter-invocation-counts res)))
      (is (= 0 (:leave-invocation-counts res)))
      (is (= 0 (:error-invocation-counts res)) "Error chain was invoked with no error thrown"))))

(deftest error-chain-is-invoked-when-async-enter-returns-an-exception
  (go-test
    (let [the-exception (ex-info "the exception" {})
          ixs [track-error-ix
               track-error-ix
               (merge (async-returns-error-ix :enter the-exception)
                      track-error-ix)]
          c (ix/execute invocation-tracking-ctx ixs)
          timeout (async/timeout 100)
          [res p] (alts! [c timeout])]
      (is (= p c))
      (is (= the-exception (:lambda-toolshed.papillon/error res)))
      (is (= 3 (:error-invocation-counts res))))))

(deftest error-chain-is-invoked-when-async-leave-returns-an-exception
  (go-test
    (let [the-exception (ex-info "the exception" {})
          ixs [track-error-ix
               track-error-ix
               (merge (async-returns-error-ix :leave the-exception)
                      track-error-ix)]
          c (ix/execute invocation-tracking-ctx ixs)
          timeout (async/timeout 100)
          [res p] (alts! [c timeout])]
      (is (= p c))
      (is (= the-exception (:lambda-toolshed.papillon/error res)))
      (is (= 2 (:error-invocation-counts res))))))

(deftest leave-chain-is-reinvoked-when-an-error-processor-removed-the-error-key-in-async-error-handler
  (go-test
    (let [the-exception (ex-info "the exception" {})
          ixs [track-leave-ix
               track-leave-ix
               {:error (fn [ctx]
                         (go
                           (-> ctx
                               (#(track-invocation :error %))
                               (dissoc :lambda-toolshed.papillon/error))))}
               (merge (async-returns-error-ix :leave the-exception)
                      track-error-ix)]
          c (ix/execute invocation-tracking-ctx ixs)
          timeout (async/timeout 100)
          [res p] (alts! [c timeout])]
      (is (= p c))
      (is (not (contains? res :lambda-toolshed.papillon/error)))
      (is (= 1 (:error-invocation-counts res)))
      (is (= 2 (:leave-invocation-counts res))))))
