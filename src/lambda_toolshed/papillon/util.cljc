(ns lambda-toolshed.papillon.util)

(defn error?
  "Is the given value `x` an exception?"
  [x]
  #?(:clj (instance? Throwable x)
     :cljs (instance? js/Error x)))
