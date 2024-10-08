(ns lambda-toolshed.papillon-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.test-utils :refer [runt! runt-fn! test-async] :include-macros true]))

(def ix {:name :ix :enter identity :leave identity :error identity})
(def exception (ex-info "the exception" {}))
(def ix-throw-on-enter {:name :ix-throw-on-enter :enter (fn [_] (throw exception))})
(def ix-throw-on-leave {:name :ix-throw-on-leave :leave (fn [_] (throw exception))})
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
  (let [ctx (ix/initialize [ix-counter] {::ix/trace [] ::x true})
        expected-trace [[:ix-counter :enter] [:ix-counter :leave]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
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
          (ix/execute ctx cb))))))

(deftest allows-for-empty-chain-of-interceptors
  (let [ixs []
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace []]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest allows-for-interceptor-chain-of-only-enters
  (let [ixs [{:name :ix :enter identity}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix :enter] [:ix :leave]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest allows-for-interceptor-chain-of-only-leaves
  (let [ixs [{:name :ix :leave identity}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix :enter] [:ix :leave]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest allows-for-interceptor-chain-of-only-errors
  (let [ixs [{:name :ix :error identity}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix :enter] [:ix :leave]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest exception-semantics-are-preserved
  (let [ixs [ix-throw-on-enter]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix :enter] [:ix :error]]]
    (testing "sync"
      (is (thrown-with-msg?
           #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
           #"the exception"
           (ix/execute ctx))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= exception result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest error-chain-is-invoked-when-enter-throws-an-exception
  (let [ixs [ix-catch ix-throw-on-enter]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix-catch :enter]
                        [:ix-throw-on-enter :enter]
                        [:ix-throw-on-enter :error]
                        [:ix-catch :error]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest error-chain-is-invoked-when-leave-throws-an-exception
  (let [ixs [ix-catch ix-throw-on-leave]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix-catch :enter]
                        [:ix-throw-on-leave :enter]
                        [:ix-throw-on-leave :leave]
                        [:ix-catch :error]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest interceptors-can-return-chrysalises
  (let [ixs [{:name :ix-chrysalis
              :enter (fn [ctx]
                       #?(:clj (let [p (promise)] (deliver p ctx) p)
                          :cljs (js/Promise.resolve ctx)))}
             ix]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix-chrysalis :enter]
                        [:ix :enter]
                        [:ix :leave]
                        [:ix-chrysalis :leave]]]
    #?(:clj (testing "sync"
              (let [result (ix/execute ctx)]
                (is (= expected-trace (::ix/trace result)))
                (is (::x result)))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

(deftest error-chain-is-invoked-when-enter-returns-an-exception-chrysalis
  (let [p #?(:clj (let [p (promise)] (deliver p exception) p)
             :cljs (js/Promise.resolve exception))
        ixs [ix-catch
             {:name :ix-thrown-chrysalis :enter (constantly p)}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix-catch :enter]
                        [:ix-thrown-chrysalis :enter]
                        [:ix-thrown-chrysalis :error]
                        [:ix-catch :error]]]
    #?(:clj (testing "sync"
              (let [result (ix/execute ctx)]
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
          (ix/execute ctx cb))))))

(deftest lost-context-triggers-exception
  (let [ixs [ix-catch {:name :loser :enter (constantly nil)}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix-catch :enter]
                        [:loser :enter]
                        [:loser :error]
                        [:ix-catch :error]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (= "Context was lost at [:loser :enter]!" (ex-message (result ::error))))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (= "Context was lost at [:loser :enter]!" (ex-message (result ::error))))
                   (done))]
          (ix/execute ctx cb))))))

(deftest error-chain-is-invoked-when-leave-returns-an-exception-chrysalis
  (let [p #?(:clj (let [p (promise)] (deliver p exception) p)
             :cljs (js/Promise.resolve exception))
        ixs [ix-catch
             {:name :ix-thrown-chrysalis :leave (constantly p)}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix-catch :enter]
                        [:ix-thrown-chrysalis :enter]
                        [:ix-thrown-chrysalis :leave]
                        [:ix-catch :error]]]
    #?(:clj (testing "sync"
              (let [result (ix/execute ctx)]
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
          (ix/execute ctx cb))))))

(deftest leave-chain-is-resumed-when-error-processor-removes-error-key
  (let [ixs [ix ix-catch ix-throw-on-leave]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[:ix :enter]
                        [:ix-catch :enter]
                        [:ix-throw-on-leave :enter]
                        [:ix-throw-on-leave :leave]
                        [:ix-catch :error]
                        [:ix :leave]]]
    (testing "sync"
      (let [result (ix/execute ctx)]
        (is (= expected-trace (::ix/trace result)))
        (is (::x result))))
    (testing "async"
      (test-async done
        (let [cb (fn [result]
                   (is (= expected-trace (::ix/trace result)))
                   (is (::x result))
                   (done))]
          (ix/execute ctx cb))))))

#_(deftest reduced-context-stops-enter-chain-processing
    (let [ixs [{:name :reducer :enter reduced} ix]
          ctx (ix/initialize ixs {::ix/trace [] ::x true})
          expected-trace [[:reducer :enter]
                          [:reducer :leave]]]
      (testing "sync"
        (let [result (ix/execute ctx)]
          (is (= expected-trace (::ix/trace result)))
          (is (::x result))))
      (testing "async"
        (test-async done
          (let [cb (fn [result]
                     (is (= expected-trace (::ix/trace result)))
                     (is (::x result))
                     (done))]
            (ix/execute ctx cb))))))
