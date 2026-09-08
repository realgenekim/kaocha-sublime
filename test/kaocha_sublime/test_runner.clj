(ns kaocha-sublime.test-runner
  (:require [clojure.test :as t] [kaocha-sublime.plugins-test]))
(defn -main [& _]
  (let [r (t/run-tests 'kaocha-sublime.plugins-test)]
    (shutdown-agents)
    (System/exit (+ (:fail r) (:error r)))))
