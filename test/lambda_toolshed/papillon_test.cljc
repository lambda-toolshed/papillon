(ns lambda-toolshed.papillon-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.test-utils :refer [runt! runt-fn! test-async] :include-macros true]))

(def ix {:name :ix :enter identity :leave identity :error identity})
(def exception (ex-info "the exception" {}))
(def ix-throw-on-enter {:name :ix-throw-on-enter :enter (fn [_] (throw exception))})
(def ix-throw-on-leave {:name :ix-throw-on-leave :leave (fn [_] (throw exception))})
(def ix-throw-on-error {:name :ix-throw-on-error :error (fn [_] (throw exception))})
(def ix-catch
  {:name :ix-catch
   :error (fn [{error ::ix/error :as ctx}]
            (-> ctx
                (dissoc ::ix/error)
                (assoc ::error error)))})

(def ix-counter {:name :ix-counter
                 :enter #(update % :enter (fnil inc 0))
                 :leave #(update % :leave (fnil inc 0))
                 :error #(update % :error (fnil inc 0))})

(def $ctx {::ix/trace [] ::x true})

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
      (is (= (::ix/queue ctx) (apply conj ixs ixs2)))))
  (testing "enqueues resolved vars"
    (let [ixs []
          ctx (ix/enqueue (ix/initialize [] {}) [#'ix-counter])]
      (is (= ix-counter (-> ctx ::ix/queue first))))))

(deftest clear-queue
  (testing "clears the context's queue"
    (let [ixs [{:enter identity}]
          ctx (ix/clear-queue (ix/enqueue {} ixs))]
      (is (empty? (ctx ::ix/queue))))))

(deftest baseline
  (let [ixs [ix-counter]
        expected-trace [[:ix-counter :enter] [:ix-counter :leave] [:ix-counter :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (= 1 (:enter result)))
        (is (= 1 (:leave result)))
        (is (nil? (:error result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (= 1 (:enter result)))
                   (is (= 1 (:leave result)))
                   (is (nil? (:error result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest allows-for-empty-chain-of-interceptors
  (let [ixs []
        expected-trace []]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest allows-for-interceptor-chain-of-only-enters
  (let [ixs [{:name :ix :enter identity}]
        expected-trace [[:ix :enter] [:ix :leave] [:ix :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest allows-for-interceptor-chain-of-only-leaves
  (let [ixs [{:name :ix :leave identity}]
        expected-trace [[:ix :enter] [:ix :leave] [:ix :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest allows-for-interceptor-chain-of-only-errors
  (let [ixs [{:name :ix :error identity}]
        expected-trace [[:ix :enter] [:ix :leave] [:ix :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest exception-semantics-are-preserved
  (let [ixs [ix-throw-on-enter]
        expected-trace [[:ix :enter] [:ix :error]]]
    (testing "sync"
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
           #"the exception"
           (ix/execute ixs $ctx))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= exception result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest error-chain-is-invoked-when-enter-throws-an-exception
  (let [ixs [ix-catch ix-throw-on-enter]
        expected-trace [[:ix-catch :enter]
                        [:ix-throw-on-enter :enter]
                        [:ix-throw-on-enter :error]
                        [:ix-throw-on-enter :final]
                        [:ix-catch :error]
                        [:ix-catch :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest error-chain-is-invoked-when-leave-throws-an-exception
  (let [ixs [ix-catch ix-throw-on-leave]
        expected-trace [[:ix-catch :enter]
                        [:ix-throw-on-leave :enter]
                        [:ix-throw-on-leave :leave]
                        [:ix-throw-on-leave :error]
                        [:ix-throw-on-leave :final]
                        [:ix-catch :error]
                        [:ix-catch :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest interceptors-can-return-chrysalises
  (let [ixs [{:name :ix-chrysalis
              :enter (fn [ctx]
                       #?(:clj (let [p (promise)] (deliver p ctx) p)
                          :cljs (js/Promise.resolve ctx)))}
             ix]
        expected-trace [[:ix-chrysalis :enter]
                        [:ix :enter]
                        [:ix :leave]
                        [:ix :final]
                        [:ix-chrysalis :leave]
                        [:ix-chrysalis :final]]]
    #?(:clj (testing "sync"
              (let [result (ix/execute ixs $ctx)]
                (is (= expected-trace (::ix/trace result)))
                (is (::x result)))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest error-chain-is-invoked-when-enter-returns-an-exception-chrysalis
  (let [p #?(:clj (let [p (promise)] (deliver p exception) p)
             :cljs (js/Promise.resolve exception))
        ixs [ix-catch
             {:name :ix-thrown-chrysalis :enter (constantly p)}]
        expected-trace [[:ix-catch :enter]
                        [:ix-thrown-chrysalis :enter]
                        [:ix-thrown-chrysalis :error]
                        [:ix-thrown-chrysalis :final]
                        [:ix-catch :error]
                        [:ix-catch :final]]]
    #?(:clj (testing "sync"
              (let [result (ix/execute ixs $ctx)]
                (is (= expected-trace (::ix/trace result)))
                (is (::x result))
                (is (= exception (::error result))))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (is (= exception (::error result)))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest lost-context-triggers-exception
  (let [ixs [ix-catch {:name :loser :enter (constantly nil)}]
        expected-trace [[:ix-catch :enter]
                        [:loser :enter]
                        [:loser :error]
                        [:loser :final]
                        [:ix-catch :error]
                        [:ix-catch :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (= "Context was lost at [:loser :enter]!" (ex-message (result ::error))))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (= "Context was lost at [:loser :enter]!" (ex-message (result ::error))))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest error-chain-is-invoked-when-leave-returns-an-exception-chrysalis
  (let [p #?(:clj (let [p (promise)] (deliver p exception) p)
             :cljs (js/Promise.resolve exception))
        ixs [ix-catch
             {:name :ix-thrown-chrysalis :leave (constantly p)}]
        expected-trace [[:ix-catch :enter]
                        [:ix-thrown-chrysalis :enter]
                        [:ix-thrown-chrysalis :leave]
                        [:ix-thrown-chrysalis :error]
                        [:ix-thrown-chrysalis :final]
                        [:ix-catch :error]
                        [:ix-catch :final]]]
    #?(:clj (testing "sync"
              (let [result (ix/execute ixs $ctx)]
                (is (= expected-trace (::ix/trace result)))
                (is (::x result))
                (is (= exception (::error result))))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (is (= exception (::error result)))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest leave-chain-is-resumed-when-error-processor-removes-error-key
  (let [ixs [ix ix-catch ix-throw-on-leave]
        expected-trace [[:ix :enter]
                        [:ix-catch :enter]
                        [:ix-throw-on-leave :enter]
                        [:ix-throw-on-leave :leave]
                        [:ix-throw-on-leave :error]
                        [:ix-throw-on-leave :final]
                        [:ix-catch :error]
                        [:ix-catch :final]
                        [:ix :leave]
                        [:ix :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest reduced-context-stops-enter-chain-processing
  (let [ixs [{:name :reducer :enter reduced} ix]
        expected-trace [[:reducer :enter]
                        [:reducer :leave]
                        [:reducer :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))

(deftest error-chain-is-continued-on-consecutive-throws
  (let [ixs [ix ix-catch ix-throw-on-error ix-throw-on-leave]
        expected-trace [[:ix :enter]
                        [:ix-catch :enter]
                        [:ix-throw-on-error :enter]
                        [:ix-throw-on-leave :enter]
                        [:ix-throw-on-leave :leave]
                        [:ix-throw-on-leave :error]
                        [:ix-throw-on-leave :final]
                        [:ix-throw-on-error :error]
                        [:ix-throw-on-error :final]
                        [:ix-catch :error]
                        [:ix-catch :final]
                        [:ix :leave]
                        [:ix :final]]]
    (testing "sync"
      (let [result (ix/execute ixs $ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ixs $ctx cb))))))
