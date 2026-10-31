(ns kaocha-sublime.test-runner
  (:require [clojure.test :as t] [kaocha-sublime.plugins-test]
            [kaocha-sublime.receipt-test] [kaocha-sublime.affected-test]
            [kaocha-sublime.agent-test] [kaocha-sublime.defects-test] [kaocha-sublime.status-test] [kaocha-sublime.intent-contract-test]
            [kaocha-sublime.runner-test] [kaocha-sublime.stale-test] [kaocha-sublime.leak-test]))
(defn -main [& _]
  (let [r (t/run-tests 'kaocha-sublime.plugins-test 'kaocha-sublime.receipt-test
                       'kaocha-sublime.affected-test 'kaocha-sublime.status-test
                       'kaocha-sublime.intent-contract-test 'kaocha-sublime.runner-test 'kaocha-sublime.stale-test 'kaocha-sublime.leak-test 'kaocha-sublime.agent-test 'kaocha-sublime.defects-test)]
    (shutdown-agents)
    (System/exit (+ (:fail r) (:error r)))))
