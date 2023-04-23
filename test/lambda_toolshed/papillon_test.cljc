(ns lambda-toolshed.papillon-test
  (:require
   #?(:cljs
      [cljs.core.async.interop :refer [<p!]])
   [clojure.core.async :as async :refer [alts! go]]
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.papillon.async.core_async]
   [lambda-toolshed.test-utils :refer [go-test] :include-macros true]))

(def ^:private capture-ix
  {:name :capture
   :error (fn [{error ::ix/error :as ctx}]
            (-> ctx
                (dissoc ::ix/error)
                (assoc ::error error)))})

(defn ->async
  "Convert the synchronous interceptor `itx` to the async equivalent"
  [itx]
  (-> itx
      (update :enter #(when % (fn [ctx] (async/go (% ctx)))))
      (update :leave #(when % (fn [ctx] (async/go (% ctx)))))
      (update :error #(when % (fn [ctx] (async/go (% ctx)))))))

(def async-ix (->async {:name :->async :enter identity}))

(deftest enqueue
  (testing "enqueues interceptors to an empty context"
    (let [ixs [{:enter identity}]
          ctx (ix/enqueue {} ixs)]
      (is (::ix/queue ctx))
      (is (= (::ix/queue ctx) ixs))))
  (testing "enqueues interceptors to existing interceptors"
    (let [ixs [{} {} {}]
          ixs2 [{}]
          ctx (ix/enqueue (ix/enqueue {} ixs) ixs2)]
      (is (::ix/queue ctx))
      (is (= (::ix/queue ctx) (apply conj ixs ixs2))))))

(deftest clear-queue
  (testing "clears the context's queue"
    (let [ixs [{:enter identity}]
          ctx (ix/clear-queue (ix/enqueue {} ixs))]
      (is (empty? (ctx ::ix/queue))))))

(deftest allows-for-empty-chain-of-interceptors
  (is (= {::ix/queue #?(:clj clojure.lang.PersistentQueue/EMPTY
                        :cljs cljs.core/PersistentQueue.EMPTY)
          ::ix/stack []}
         (ix/execute [] {}))))

(deftest allows-for-interceptor-chain-of-only-enters
  (let [ixs [{:name :ix :enter identity}]
        expected-log [[:ix :enter] [:ix :leave]]
        res (ix/execute ixs {})]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (empty? (::ix/queue res)))
      (is (empty? (::ix/stack res)))
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (mapv ->async ixs)
           [res _] (async/alts! [(ix/execute ixs {::ix/trace []})
                                 (async/timeout 10)])]
       (is (map? res))
       (is (empty? (::ix/queue res)))
       (is (empty? (::ix/stack res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest allows-for-interceptor-chain-of-only-leaves
  (let [ixs [{:name :ix :leave identity}]
        expected-log [[:ix :enter] [:ix :leave]]]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (empty? (::ix/queue res)))
      (is (empty? (::ix/stack res)))
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (mapv ->async ixs)
           [res _] (async/alts! [(ix/execute ixs {::ix/trace []})
                                 (async/timeout 10)])]
       (is (map? res))
       (is (empty? (::ix/queue res)))
       (is (empty? (::ix/stack res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest allows-for-interceptor-chain-of-only-errors
  (let [ixs [{:name :ix :error identity}]
        expected-log [[:ix :enter] [:ix :leave]]
        res (ix/execute ixs {::ix/trace []})]
    (is (empty? (::ix/queue res)))
    (is (empty? (::ix/stack res)))
    (is (= expected-log (::ix/trace res))))
  (go-test
   "Nothing to be done; chain can't start async processing with only error fns"))

(deftest error-stage-is-never-invoked-if-no-error-has-been-thrown
  (let [ixs [{:name :ix :enter identity :leave identity :error identity}]
        expected-log [[:ix :enter] [:ix :leave]]]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (map? res))
      (is (empty? (::ix/queue res)))
      (is (empty? (::ix/stack res)))
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (mapv ->async ixs)
           [res _] (async/alts! [(ix/execute ixs {::ix/trace []})
                                 (async/timeout 10)])]
       (is (map? res))
       (is (empty? (::ix/queue res)))
       (is (empty? (::ix/stack res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest exception-semantics-are-preserved
  (let [the-exception (ex-info "the exception" {})
        ixs [{:name :ix :enter (fn [_] (throw the-exception))}]]
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
         #"the exception"
         (ix/execute ixs {})))
    (go-test
     (let [ixs (concat [(->async {:enter identity})] ; transition to async
                       ixs)
           [res _] (async/alts! [(ix/execute ixs {})
                                 (async/timeout 10)])]
       (is (= the-exception res))))))

(deftest error-chain-is-invoked-when-enter-throws-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             {:name :thrower :enter (fn [_] (throw the-exception))}]
        expected-log [[:capture :enter]
                      [:thrower :enter]
                      [:thrower :error]
                      [:capture :error]]]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (= the-exception (::error res)))
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (concat [async-ix] ixs)
           expected-log (concat [[:->async :enter]] expected-log [[:->async :leave]])
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (= the-exception (::error res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest error-chain-is-invoked-when-enter-asynchronously-returns-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             {:name :thrower :enter (fn [_] (go the-exception))}]
        expected-log [[:capture :enter]
                      [:thrower :enter]
                      [:thrower :error]
                      [:capture :error]]]
    (go-test
     (let [ixs (concat [async-ix] ixs)
           expected-log (concat [[:->async :enter]] expected-log [[:->async :leave]])
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (= the-exception (::error res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest error-chain-is-invoked-when-leave-throws-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             {:name :thrower :leave (fn [_] (throw the-exception))}]
        expected-log [[:capture :enter]
                      [:thrower :enter]
                      [:thrower :leave]
                      [:capture :error]]]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (= the-exception (::error res)))
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (concat [async-ix] ixs)
           expected-log (concat [[:->async :enter]] expected-log [[:->async :leave]])
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (= the-exception (::error res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest lost-context-triggers-exception
  (let [ixs [capture-ix {:name :loser
                         :enter (constantly nil)}]
        expected-log [[:capture :enter]
                      [:loser :enter]
                      [:loser :error]
                      [:capture :error]]
        res (ix/execute ixs {::ix/trace []})]
    (is (= expected-log (::ix/trace res)))
    (is (= "Context was lost!" (ex-message (res ::error))))
    (go-test
     (let [ixs [capture-ix {:name :loser
                            :enter (constantly (doto (async/chan)
                                                 async/close!))}]
           expected-log [[:capture :enter]
                         [:loser :enter]
                         [:loser :error]
                         [:capture :error]]
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (= "Context was lost!" (ex-message (res ::error))))
       (is (= expected-log (::ix/trace res)))))))

(deftest error-chain-is-invoked-when-leave-asynchronously-returns-an-exception
  (let [the-exception (ex-info "the exception" {})
        ixs [capture-ix
             {:name :thrower :leave (fn [_] (go the-exception))}]
        expected-log [[:capture :enter]
                      [:thrower :enter]
                      [:thrower :leave]
                      [:capture :error]]]
    (go-test
     (let [ixs (concat [async-ix] ixs)
           expected-log (concat [[:->async :enter]] expected-log [[:->async :leave]])
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (= the-exception (::error res)))
       (is (= expected-log (::ix/trace res)))))))

(deftest async-lost-context-triggers-exception
  (go-test
   (let [ixs [capture-ix {:name :loser
                          :enter (constantly (doto (async/chan)
                                               async/close!))}]
         expected-log [[:capture :enter]
                       [:loser :enter]
                       [:loser :error]
                       [:capture :error]]
         [res _] (alts! [(ix/execute ixs {::ix/trace []})
                         (async/timeout 10)])]
     (is (map? res))
     (is (= "Context was lost!" (ex-message (res ::error))))
     (is (= expected-log (::ix/trace res))))))

(deftest leave-chain-is-resumed-when-error-processor-removes-error-key
  (let [the-exception (ex-info "the exception" {})
        ixs [{:name :ix :enter identity}
             capture-ix
             {:name :thrower :leave (fn [_] (throw the-exception))}]
        expected-log [[:ix :enter]
                      [:capture :enter]
                      [:thrower :enter]
                      [:thrower :leave]
                      [:capture :error]
                      [:ix :leave]]]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (not (contains? res ::ix/error)))
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (update ixs 0 ->async)
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (not (contains? res ::ix/error)))
       (is (= expected-log (::ix/trace res)))))))

(deftest reduced-context-stops-enter-chain-processing
  (let [ixs [{:name :reducer :enter reduced}
             {:name :ix}]
        expected-log [[:reducer :enter] [:reducer :leave]]]
    (let [res (ix/execute ixs {::ix/trace []})]
      (is (= expected-log (::ix/trace res))))
    (go-test
     (let [ixs (update ixs 0 ->async)
           [res _] (alts! [(ix/execute ixs {::ix/trace []})
                           (async/timeout 10)])]
       (is (map? res))
       (is (= expected-log (::ix/trace res)))))))

#?(:cljs
   (deftest allows-for-promise-success-return-values
     (let [ixs [{:name :ix}
                {:name :promiser :enter (fn [x] (js/Promise.resolve x))}]
           expected-log [[:ix :enter] [:promiser :enter] [:promiser :leave] [:ix :leave]]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))

#?(:cljs
   (deftest allows-for-promise-rejection-return-values
     (let [the-exception (ex-info "the exception" {})
           ixs [{:name :ix}
                capture-ix
                {:name :promise-rejector :enter (fn [_] (js/Promise.reject the-exception))}]
           expected-log [[:ix :enter]
                         [:capture :enter]
                         [:promise-rejector :enter]
                         [:promise-rejector :error]
                         [:capture :error]
                         [:ix :leave]]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= (ex-message the-exception) (ex-message (res ::error))))
          (is (= expected-log (::ix/trace res))))))))

#?(:cljs
   (deftest allows-for-presenting-as-a-promise
     (let [ixs [{:name :ix}
                {:name :promiser :enter (fn [x] (js/Promise.resolve x))}]
           ctx {::ix/trace []
                ::ix/present-async ix/present-promise}
           expected-log [[:ix :enter] [:promiser :enter] [:promiser :leave] [:ix :leave]]]
       (go-test
        (let [res (<p! (js/Promise.race [(ix/execute ixs ctx)
                                         (js/Promise. (fn [_resolve reject]
                                                        (js/setTimeout reject 10 (ex-info "timed out" {}))))]))]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))
