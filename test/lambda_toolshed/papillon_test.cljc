(ns lambda-toolshed.papillon-test
  (:require
   #?(:cljs [cljs.core.async.interop :refer [p->c]])
   #?(:cljs [lambda-toolshed.papillon.util :refer [error?]])
   [clojure.core.async :as async :refer [alts! go]]
   [clojure.test :as test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.papillon.async.core-async]
   [lambda-toolshed.test-utils :refer [go-test] :include-macros true]))

(def ^:private capture-ix
  {:name :capture
   :error (fn [{error ::ix/error :as ctx}]
            (-> ctx
                (dissoc ::ix/error)
                (assoc ::error error)))})

#?(:cljs
   (defn timeout-P
     "returns a promise that times out after x milliseconds"
     [x]
     (js/Promise. (fn [resolve _reject]
                    (js/setTimeout resolve x "timed out")))))

#?(:cljs
   (def promiser-ix
     {:name :promiser :enter (fn [ctx] (js/Promise.resolve ctx))}))

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
           ix-chan (ix/execute ixs {::ix/trace []})
           [res c] (alts! [ix-chan
                           (async/timeout 10)])]
       (is (= ix-chan c))
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
    (is (= "Context was lost at [:loser :enter]!" (ex-message (res ::error))))
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
       (is (= "Context was lost at [:loser :enter]!" (ex-message (res ::error))))
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
     (is (= "Context was lost at [:loser :enter]!" (ex-message (res ::error))))
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

#?(:clj
   (deftest allows-for-future-success-return-values
     (let [ixs [{:name :futurist :enter (fn [x] (future x))}]
           expected-log [[:futurist :enter] [:futurist :leave]]]
       (let [res (ix/execute ixs {::ix/trace []})]
         (is (map? res))
         (is (empty? (::ix/queue res)))
         (is (empty? (::ix/stack res)))
         (is (= expected-log (::ix/trace res))))
       (go-test
        (let [ixs (concat [async-ix] ixs)
              expected-log (concat [[:->async :enter]] expected-log [[:->async :leave]])
              [res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))

#?(:clj
   (deftest error-chain-is-invoked-when-enter-throws-an-exception-in-a-future
     (let [the-exception (ex-info "the exception" {})
           ixs [capture-ix
                {:name :future-thrower :enter (fn [_] (future (throw the-exception)))}]
           expected-log [[:capture :enter]
                         [:future-thrower :enter]
                         [:future-thrower :error]
                         [:capture :error]]]
       (let [res (ix/execute ixs {::ix/trace []})]
         (is (= the-exception (ex-cause (::error res))))
         (is (= expected-log (::ix/trace res))))
       (go-test
        (let [ixs (concat [async-ix] ixs)
              expected-log (concat [[:->async :enter]] expected-log [[:->async :leave]])
              [res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (= the-exception (ex-cause (::error res))))
          (is (= expected-log (::ix/trace res))))))))

#?(:clj
   (deftest allows-for-presenting-futures-as-channel
     (let [ixs [async-ix
                {:name :futurist :enter (fn [x] (future x))}]
           expected-log [[:->async :enter] [:futurist :enter] [:futurist :leave] [:->async :leave]]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))

#?(:clj
   (deftest allows-for-presenting-channels-as-future
     (let [ixs [{:name :futurist :enter (fn [x] (future x))}
                async-ix]
           expected-log [[:futurist :enter] [:->async :enter] [:->async :leave] [:futurist :leave]]]
       (go-test
        (let [ix-chan (ix/execute ixs {::ix/trace []}) 
              [res c] (alts! [ix-chan
                              (async/timeout 10)])]
          (is (= ix-chan c))
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))

#?(:cljs
   (deftest allows-for-promise-success-return-values-as-channel
     (let [ixs [async-ix
                promiser-ix]
           expected-log [[:->async :enter] [:promiser :enter] [:promiser :leave] [:->async :leave]]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))

#?(:cljs
   (deftest allows-for-promise-rejection-return-values-as-channel
     (let [the-exception (ex-info "the exception" {})
           ixs [async-ix
                capture-ix
                {:name :promise-rejector :enter (fn [_] (js/Promise.reject the-exception))}]
           expected-log [[:->async :enter]
                         [:capture :enter]
                         [:promise-rejector :enter]
                         [:promise-rejector :error]
                         [:capture :error]
                         [:->async :leave]]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= (ex-message the-exception) (ex-message (::error res))))
          (is (= expected-log (::ix/trace res))))))))

#?(:cljs
   (deftest allows-for-presenting-rejected-promise-as-channel
     (let [the-exception (ex-info "the exception" {})
           ixs [async-ix
                {:name :promise-rejector :enter (fn [_] (js/Promise.reject the-exception))}]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (error? res))
          (is (= (ex-message the-exception) (ex-message res))))))))

#?(:cljs
   (deftest allows-for-deeply-mixing-promises-and-channels-when-promise-resolves
     (let [ixs [async-ix
                promiser-ix
                async-ix
                promiser-ix
                async-ix
                promiser-ix]
           expected-log [[:->async :enter]
                         [:promiser :enter]
                         [:->async :enter]
                         [:promiser :enter]
                         [:->async :enter]
                         [:promiser :enter]
                         [:promiser :leave]
                         [:->async :leave]
                         [:promiser :leave]
                         [:->async :leave]
                         [:promiser :leave]
                         [:->async :leave]]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (map? res))
          (is (empty? (::ix/queue res)))
          (is (empty? (::ix/stack res)))
          (is (= expected-log (::ix/trace res))))))))

#?(:cljs
   (deftest allows-for-deeply-mixing-promises-and-channels-when-promise-rejects
     (let [the-exception (ex-info "the exception" {})
           ixs [async-ix
                promiser-ix
                async-ix
                promiser-ix
                async-ix
                {:name :promise-rejector :enter (fn [_] (js/Promise.reject the-exception))}]]
       (go-test
        (let [[res _] (alts! [(ix/execute ixs {::ix/trace []})
                              (async/timeout 10)])]
          (is (error? res))
          (is (= (ex-message the-exception) (ex-message res))))))))

#?(:cljs
   (deftest allows-for-presenting-resolved-promise
     (let [ixs [{:name :ix}
                promiser-ix]
           expected-log [[:ix :enter] [:promiser :enter] [:promiser :leave] [:ix :leave]]]
       (test/async
        done
        (let [p (js/Promise.race [(ix/execute ixs {::ix/trace []})
                                  (timeout-P 10)])]
          (-> p
              (.then (fn [res]
                       (is (map? res))
                       (is (empty? (::ix/queue res)))
                       (is (empty? (::ix/stack res)))
                       (is (= expected-log (::ix/trace res)))))
              (.catch (fn [res]
                        (is false (pr-str "Promise was rejected with: " res))))
              (.then (fn [_] (done)))))))))

#?(:cljs
   (deftest allows-for-presenting-rejected-promise
     (let [the-exception (ex-info "the exception" {})
           ixs [{:name :ix}
                {:name :promise-rejector :enter (fn [_] (js/Promise.reject the-exception))}]]
       (test/async
        done
        (let [p (js/Promise.race [(ix/execute ixs {::ix/trace []})
                                  (timeout-P 10)])]
          (-> p
              (.then (fn [res] (is false (pr-str res))))
              (.catch (fn [res]
                        (is (error? res))
                        (is (= (ex-message the-exception) (ex-message res)))))
              (.then (fn [_] (done)))))))))

#?(:cljs
   (deftest allows-for-presenting-rejected-promise-from-leave
     (let [the-exception (ex-info "the exception" {})
           ixs [{:name :ix}
                {:name :promise-leave-rejector
                 :enter (fn [ctx] (js/Promise.resolve ctx))
                 :leave (fn [_] (js/Promise.reject the-exception))}]]
       (test/async
        done
        (let [p (js/Promise.race [(ix/execute ixs {::ix/trace []})
                                  (timeout-P 10)])]
          (-> p
              (.then (fn [res] (is false (pr-str res))))
              (.catch (fn [res]
                        (is (error? res))
                        (is (= (ex-message the-exception) (ex-message res)))))
              (.then (fn [_] (done)))))))))

#?(:cljs
   (deftest allows-for-presenting-channel-as-resolved-promise
     (let [ixs [{:name :ix}
                promiser-ix
                async-ix]
           expected-log [[:ix :enter] [:promiser :enter] [:->async :enter] [:->async :leave] [:promiser :leave] [:ix :leave]]]
       (test/async
        done
        (let [p (js/Promise.race [(ix/execute ixs {::ix/trace []})
                                  (timeout-P 10)])]
          (-> p
              (.then (fn [res]
                       (is (map? res))
                       (is (empty? (::ix/queue res)))
                       (is (empty? (::ix/stack res)))
                       (is (= expected-log (::ix/trace res)))))
              (.catch (fn [res]
                        (is false (pr-str "Promise was rejected with: " res))))
              (.then (fn [_] (done)))))))))
