(ns lambda-toolshed.papillon.channels-test
  "Tests that demonstrate the opt-in ability of papillon to work seamlessly
  with clojure.core.async channels -either as the deferred output of an interceptor
  or as the output of the overall chain execution."
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [lambda-toolshed.papillon :as ix]
   [lambda-toolshed.papillon.channels]
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

(deftest channels-as-chrysalis
  (let [ixs [{:name :ix-chrysalis
              :enter (fn [ctx] (async/go (assoc ctx ::hello true)))}
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

(deftest deferred-execution-result
  (let [ixs [{:name ::hello
              :enter (fn [ctx] (async/go (assoc ctx ::hello true)))}
             {:name ::world
              :leave (fn [ctx] (assoc ctx ::world true))}]
        ctx (ix/initialize ixs {::ix/trace [] ::x true})
        expected-trace [[::hello :enter]
                        [::world :enter]
                        [::world :leave]
                        [::hello :leave]]]
    (test-async done
      (let [c (async/chan)
            callback (partial async/put! c)]
        (ix/execute ctx callback)
        (async/go (when-let [result (async/alt! c ([ctx] ctx)
                                                (async/timeout 10) nil)]
                    (is (= expected-trace (::ix/trace result)))
                    (is (::x result))
                    (is (::hello result))
                    (is (::world result)))
                  (done))))))
