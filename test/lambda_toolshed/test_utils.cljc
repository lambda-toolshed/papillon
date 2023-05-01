(ns lambda-toolshed.test-utils
  (:require [clojure.core.async :as async]
            [clojure.test :as test]))

(defmacro go-test
  "Asynchronously execute the test body (in a go block)"
  [& body]
  (if (:ns &env)
    ;; In ClojureScript we execute the body as a test/async body inside a go block.
    `(let [c# (async/promise-chan)
           obj# (test/async done# (async/go (let [res# (do ~@body)] (async/>! c# res#)) (done#)))]
       (reify
         cljs.test/IAsyncTest
         cljs.core/IFn
         (~'-invoke [_# done2#] (obj# done2#) c#)))
    ;; In Clojure we block awaiting the completion of the async test block
    `(async/<!! (async/go (do ~@body)))))

(defn runt-fn!
  "`runt!` helper function"
  [f]
  (let [once-fixture-fn (test/join-fixtures (:clojure.test/once-fixtures (meta *ns*)))
        each-fixture-fn (test/join-fixtures (:clojure.test/each-fixtures (meta *ns*)))]
    (once-fixture-fn
     (fn []
       (each-fixture-fn
        (fn []
          #?(:clj (f)
             :cljs (let [f (f)]
                     (if (satisfies? cljs.test/IAsyncTest f)
                       (f (fn done []))
                       f)))))))))

(defmacro runt!
  "Run expression with fixtures"
  [& body]
  `(runt-fn! (fn [] ~@body)))
