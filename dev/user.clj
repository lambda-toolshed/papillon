(ns user
  (:require [cljs.repl.node]
            [cider.piggieback]))

(defn start-cljs
  "Starts a CLJS repl by piggiebacking onto the cider nREPL"
  []
  (cider.piggieback/cljs-repl (cljs.repl.node/repl-env)))
