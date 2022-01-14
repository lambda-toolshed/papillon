(ns lambda-toolshed.papillon.examples.dynamic-tracing)

(require '[lambda-toolshed.papillon :as papillon :refer [execute into-queue]])
(require '[clojure.core.async :as async :refer [go <! >! chan]])
(require 'clojure.pprint)

(def debug true)

(def important-ix
  (with-meta
    {:name :important-ix
     :enter (fn [ctx]
              (println "! I mark the start of an important chain of events")
              ctx)}
    {:supports-timing? true}))

(defn wrap-with-timing
  [ix]
  (let [ix-name (:name ix)
        start-timing (fn
                       [ix-name f]
                       (let [f (if f f identity)]
                         (fn [ctx]
                           (let [start-time #?(:clj (. System (currentTimeMillis))
                                               :cljs (. js/Date now))]
                             (-> ctx
                                 (assoc-in [::dynamic-tracing ix-name :start-millis] start-time)
                                 f)))))
        stop-timing (fn
                      [ix-name f]
                      (let [f (if f f identity)]
                        (fn [ctx]
                          (let [end-time #?(:clj (. System (currentTimeMillis))
                                            :cljs (. js/Date now))
                                start-time (get-in ctx [::dynamic-tracing ix-name :start-millis])
                                duration (- end-time start-time)]
                            (println "Interceptor " ix-name "took" duration "ms")
                            (-> ctx
                                (assoc-in [::dynamic-tracing ix-name :end-millis] end-time)
                                (assoc-in [::dynamic-tracing ix-name :duration-millis] duration)
                                f)))))]
    (if (:supports-timing? (meta ix))
      (-> ix
          (update :enter (partial start-timing ix-name))
          (update :leave (partial stop-timing ix-name))
          (update :error (partial stop-timing ix-name)))
      ix)))

(def instrument-timings-ix
  {:name :instrument-timings
   :enter (fn [ctx]
            (if debug
              (let [queue (:lambda-toolshed.papillon/queue ctx)
                    new-queue (into-queue (map wrap-with-timing queue))]
                (assoc ctx :lambda-toolshed.papillon/queue new-queue))
              ctx))})

(def long-running-ix
  {:name :long-running-ix
   :enter (fn [ctx]
            (println "⌛ I take a while... ⌛")
            (let [c (chan)]
              (go
                (<! (async/timeout 1000))
                (>! c (assoc ctx :long-running-result :finished)))
              c))})

(def some-more-long-running-ix
  {:name :some-more-long-running-ix
   :enter (fn [ctx]
            (println "⏳ I take some time too... ⏳")
            (let [c (chan)]
              (go
                (<! (async/timeout (rand-nth [2000 1500 2500])))
                (>! c (assoc ctx :some-more-long-running-result :finished)))
              c))})

(def varying-duration-ix
  {:name :varying-duration-ix
   :enter (fn [ctx]
            (println "⏰ I can be quick, or I can be long... ⏰")
            (let [c (chan)]
              (go
                (<! (async/timeout (rand-nth [500 500 500 5000 3500])))
                (>! c (assoc ctx :varying-duration-result :finished)))
              c))})

(def another-important-ix
  (with-meta
    {:name :another-important-ix
     :enter (fn [ctx]
              (println "❗I mark the start of an another important chain of events")
              ctx)}
    {:supports-timing? true}))

(def yet-another-important-ix
  (with-meta
    {:name :yet-another-important-ix
     :enter (fn [ctx]
              (println "‼️ I mark the start of an yet another important chain of events")
              ctx)}
    {:supports-timing? true}))

(go
  (let [c (execute {} [instrument-timings-ix
                       important-ix
                       long-running-ix
                       another-important-ix
                       some-more-long-running-ix
                       yet-another-important-ix
                       varying-duration-ix])]
    (println)
    (println "Results:")
    (clojure.pprint/pprint (<! c))))
