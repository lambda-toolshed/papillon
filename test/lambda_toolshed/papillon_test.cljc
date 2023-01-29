(ns lambda-toolshed.papillon-test
  (:require
   [clojure.core.async :as async :refer [alts! go]]
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.test-utils :refer [go-test runt! runt-fn!] :include-macros true]))

(defn- track-ix
  [stage ctx]
  (update-in ctx [:calls stage] (fnil inc 0)))

(def ^:private track-error-ix
  {:error (partial track-ix :error)})

(def ^:private track-enter-ix
  {:enter (partial track-ix :enter)})

(def ^:private track-leave-ix
  {:leave (partial track-ix :leave)})

(def ^:private track-all-ix (merge track-enter-ix track-leave-ix track-error-ix))

(defn- throw-ix
  [stage ex]
  {stage (fn [_ctx] (throw ex))})

(def ^:private capture-ix
  {:error (fn [{error :lambda-toolshed.papillon/error :as ctx}] (-> ctx
                                                                    (dissoc :lambda-toolshed.papillon/error)
                                                                    (assoc ::error error)))})

(defn ->async
  [itx]
  "Convert the synchronous interceptor `itx` to the async equivalent"
  (-> itx
      (update :enter #(when % (fn [ctx] (async/go (% ctx)))))
      (update :leave #(when % (fn [ctx] (async/go (% ctx)))))
      (update :error #(when % (fn [ctx] (async/go (% ctx)))))))

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
  (is (= {:lambda-toolshed.papillon/queue #?(:clj clojure.lang.PersistentQueue/EMPTY
                                             :cljs cljs.core/PersistentQueue.EMPTY)
          :lambda-toolshed.papillon/stack []}
         (ix/execute {} []))))

(deftest allows-for-interceptor-chain-of-only-enters
  (let [ixs [track-enter-ix]
        res (ix/execute {} ixs)]
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= {:enter 1} (:calls res))))
  (go-test
   (let [ixs [(->async track-enter-ix)]
         [res _] (async/alts! [(ix/execute {} ixs)
                               (async/timeout 10)])]
     (is (map? res))
     (is (empty? (:lambda-toolshed.papillon/queue res)))
     (is (empty? (:lambda-toolshed.papillon/stack res)))
     (is (= {:enter 1} (:calls res))))))

(deftest allows-for-interceptor-chain-of-only-leaves
  (let [ixs [track-leave-ix]
        res (ix/execute {} ixs)]
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= {:leave 1} (:calls res))))
  (go-test
   (let [ixs [(->async track-leave-ix)]
         [res _] (async/alts! [(ix/execute {} ixs)
                               (async/timeout 10)])]
     (is (map? res))
     (is (empty? (:lambda-toolshed.papillon/queue res)))
     (is (empty? (:lambda-toolshed.papillon/stack res)))
     (is (= {:leave 1} (:calls res))))))

(deftest allows-for-interceptor-chain-of-only-errors
  (let [ixs [track-error-ix]
        res (ix/execute {} ixs)]
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (nil? (:calls res))))
  (go-test
   "Nothing to be done; chain can't start async processing with only error fns"))

(deftest error-stage-is-never-invoked-if-no-error-has-been-thrown
  (let [ixs [track-all-ix]
        res (ix/execute {} ixs)]
    (is (map? res))
    (is (empty? (:lambda-toolshed.papillon/queue res)))
    (is (empty? (:lambda-toolshed.papillon/stack res)))
    (is (= {:enter 1 :leave 1} (:calls res))))
  (go-test
   (let [ixs [(->async track-all-ix)]
         [res _] (async/alts! [(ix/execute {} ixs)
                               (async/timeout 10)])]
     (is (map? res))
     (is (empty? (:lambda-toolshed.papillon/queue res)))
     (is (empty? (:lambda-toolshed.papillon/stack res)))
     (is (= {:enter 1 :leave 1} (:calls res))))))

(deftest exception-semantics-are-preserved
  (let [the-exception (ex-info "the exception" {})
        ixs [(throw-ix :enter the-exception)]]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"the exception"
         (ix/execute {} ixs))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async {:enter identity}) ; transition to async
              (throw-ix :enter the-exception)]
         [res _] (async/alts! [(ix/execute {} ixs)
                               (async/timeout 10)])]
     (is (= the-exception res)))))

(deftest error-chain-is-invoked-when-enter-throws-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             track-error-ix
             track-error-ix
             (merge (throw-ix :enter the-exception)
                    track-error-ix)]
        res (ix/execute {} ixs)]
    (is (= the-exception (::error res)))
    (is (= {:error 3} (:calls res))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async capture-ix)
              (->async track-error-ix)
              (->async track-error-ix)
              (->async track-error-ix)
              (throw-ix :enter the-exception)]
         [res _] (alts! [(ix/execute {} ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= the-exception (::error res)))
     (is (= {:error 3} (:calls res))))))

(deftest error-chain-is-invoked-when-leave-throws-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             (merge (throw-ix :leave the-exception)
                    track-error-ix)]
        res (ix/execute {} ixs)]
    (is (= the-exception (::error res)))
    (is (nil? (:calls res))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async capture-ix)
              (merge (throw-ix :leave the-exception)
                     track-error-ix)]
         [res _] (alts! [(ix/execute {} ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= the-exception (::error res)))
     (is (nil? (:calls res))))))

(deftest async-lost-context-triggers-exception
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(merge track-leave-ix track-error-ix)
              capture-ix
              {:enter (constantly (doto (async/chan)
                                    async/close!))}]
         [res _] (alts! [(ix/execute {} ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= "Context channel was closed." (ex-message (res ::error))))
     (is (= {:leave 1} (:calls res))))))

(deftest leave-chain-is-resumed-when-error-processor-removes-error-key
  (let [the-exception (ex-info "the exception" {})
        ixs [(merge track-leave-ix track-error-ix)
             capture-ix
             (merge (throw-ix :leave the-exception)
                    track-error-ix)]
        res (ix/execute {} ixs)]
    (is (not (contains? res :lambda-toolshed.papillon/error)))
    (is (= {:leave 1} (:calls res))))
  (go-test
   (let [the-exception (ex-info "the exception" {})
         ixs [(->async (merge track-leave-ix track-error-ix))
              (->async capture-ix)
              (merge (throw-ix :leave the-exception)
                     track-error-ix)]
         [res _] (alts! [(ix/execute {} ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (not (contains? res :lambda-toolshed.papillon/error)))
     (is (= {:leave 1} (:calls res))))))

(deftest reduced-context-stops-enter-chain-processing
  (let [ixs [(merge track-enter-ix track-leave-ix)
             {:enter reduced}
             track-enter-ix]
        res (ix/execute {} ixs)]
    (is (= {:enter 1 :leave 1} (:calls res))))
  (go-test
   (let [ixs [(->async (merge track-enter-ix track-leave-ix))
              (->async {:enter reduced})
              (->async track-enter-ix)]
         [res _] (alts! [(ix/execute {} ixs)
                         (async/timeout 10)])]
     (is (map? res))
     (is (= {:enter 1 :leave 1} (:calls res))))))

#?(:cljs
   (deftest allows-for-promise-return-values
     (go-test
      (let [ixs [{:enter (comp (fn [x] (js/Promise.resolve x)) (:enter track-enter-ix))}]
            [res _] (alts! [(ix/execute {} ixs)
                            (async/timeout 10)])]
        (is (map? res))
        (is (empty? (:lambda-toolshed.papillon/queue res)))
        (is (empty? (:lambda-toolshed.papillon/stack res)))
        (is (= {:enter 1} (:calls res)))))))
