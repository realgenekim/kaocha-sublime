(ns kaocha-sublime.driver
  (:require [kaocha.api :as api] [kaocha.config :as config]
            [kaocha.repl :as repl] [clojure.java.io :as io]))
(defn -main [& [mode root out since]]
  (let [cfg (config/merge-config (config/load-config nil)
             {:kaocha/tests [{:kaocha.testable/id :unit
                              :kaocha.testable/type :kaocha.type/clojure.test
                              :kaocha/ns-patterns ["-test$"]
                              :kaocha/source-paths [(str root "/src")]
                              :kaocha/test-paths [(str root "/test")]}]
              :kaocha/plugins [:kaocha.plugin/filter :kaocha.plugin/capture-output
                               :kaocha.plugin/affected :kaocha.plugin/run-receipt]
              :kaocha/reporter ['kaocha.report/documentation]
              :kaocha/color? false
              :kaocha.plugin.affected/since since
              :kaocha.plugin.run-receipt/dir out
              :kaocha.plugin.run-receipt/include-tests? true})]
    (case mode
      "repl" (dotimes [_ 2]
               (let [r (repl/run 'sample.core-test cfg)]
                 (spit (io/file out "returned.edn") (pr-str r) :append true)))
      (api/run cfg)))
  (shutdown-agents))
