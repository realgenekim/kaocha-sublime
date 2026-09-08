(ns kaocha-sublime.plugins-test
  (:require [clojure.test :refer [deftest is]]
            [kaocha.plugin.run-receipt :as receipt]
            [kaocha.plugin.affected :as affected]
            [lambdaisland.tools.namespace.dependency :as dep]))
(deftest dependency-selection-is-conservative
  (let [g (-> (dep/graph) (dep/depend 'app.test 'app.core)
              (dep/depend 'app.core 'app.leaf))
        known #{'app.test 'app.core 'app.leaf 'other.test}]
    (is (= #{'app.test 'app.core 'app.leaf}
           (affected/closure g known #{'app.leaf})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (affected/closure g known #{'missing})))) )
(deftest edn-hash-is-order-independent
  (is (= (receipt/config-hash {:a 1 :b {:c 2 :d #{3 4}}})
         (receipt/config-hash {:b {:d #{4 3} :c 2} :a 1}))))
(deftest hash-preserves-edn-collection-types
  (is (not= (receipt/config-hash {:x #{1 2}})
            (receipt/config-hash {:x [1 2]}))))

(deftest config-hash-ignores-namespace-print-sugar
  (is (= (binding [*print-namespace-maps* true] (receipt/config-hash {:app/a 1 :app/b 2}))
         (binding [*print-namespace-maps* false] (receipt/config-hash {:app/a 1 :app/b 2})))))
