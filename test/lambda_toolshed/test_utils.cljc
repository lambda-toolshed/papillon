(ns lambda-toolshed.test-utils
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.test :as test]))

(defmacro test-async
  "Asynchronously execute the test body."
  [done & body]
  (if (:ns &env)
    ;; In ClojureScript we execute the body as a test/async body, letting test/async bind the done callback.
    `(test/async ~done ~@body)
    ;; In Clojure we keep the same signature, but provide a blocking coordination function for the done "callback".
    `(let [p# (promise)
           ~done (fn [] (deliver p# true))]
       ~@body
       @p#)))

(defn runt-fn!
  "`runt!` helper function"
  [f]
  (let [once-fixture-fn (clojure.test/join-fixtures (:clojure.test/once-fixtures (meta *ns*)))
        each-fixture-fn (clojure.test/join-fixtures (:clojure.test/each-fixtures (meta *ns*)))]
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
