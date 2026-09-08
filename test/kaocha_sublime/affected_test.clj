(ns kaocha-sublime.affected-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [kaocha.plugin.affected :as affected]
            [kaocha-sublime.receipt-test :refer [temp-dir]]
            [lambdaisland.tools.namespace.dir :as dir]
            [lambdaisland.tools.namespace.track :as track]))
(defn ns-node [n]
  {:kaocha.testable/type :kaocha.type/ns :kaocha.testable/id (keyword n)
   :kaocha.ns/name n :kaocha.test-plan/tests
   [{:kaocha.testable/type :kaocha.type/var :kaocha.testable/id (keyword (str n) "test")}]})
(defn selection-fixture []
  (let [root (temp-dir)
        files {"leaf.clj" "(ns app.leaf)\n(def x 1)\n"
               "core.clj" "(ns app.core (:require [app.leaf]))\n"
               "core_test.clj" "(ns app.core-test (:require [app.core]))\n"
               "other_test.clj" "(ns app.other-test)\n"
               "resource.edn" "{:x 1}"}]
    (doseq [[f s] files] (spit (io/file root f) s))
    (let [tracker (dir/scan-dirs (track/tracker) [(io/file root)])
          cfg {:kaocha/tests [{:kaocha/source-paths [root] :kaocha/test-paths [root]}]
               :kaocha.watch/tracker tracker}
          first-cfg (affected/affected-config-hook cfg)
          cfg (assoc-in cfg [:kaocha.watch/tracker :kaocha.plugin.affected/snapshot]
                        (get-in first-cfg [:kaocha.plugin.affected/decision :snapshot]))]
      {:root root :config cfg})))
(defn select! [cfg]
  (let [r (atom nil)]
    (with-out-str
      (reset! r (affected/affected-pre-run-hook
                  (assoc (dissoc (affected/affected-config-hook cfg) :kaocha/tests)
                         :kaocha.test-plan/tests
                         [{:kaocha.testable/id :unit :kaocha.testable/type :kaocha.type/clojure.test
                           :kaocha.test-plan/tests [(ns-node 'app.core-test) (ns-node 'app.other-test)]}]))))
    @r))
(deftest leaf-source-transitive-and-unrelated-exclusion
  (let [{:keys [root config]} (selection-fixture)]
    (spit (io/file root "leaf.clj") "\n; edited" :append true)
    (let [r (select! config) s (:kaocha.plugin.affected/selection r)]
      (is (nil? (:reason s)))
      (is (= ['app.core-test] (:namespaces s)))
      (is (= ['app.core 'app.core-test 'app.leaf] (:closure s)))
      (is (true? (get-in r [:kaocha.test-plan/tests 0 :kaocha.test-plan/tests 1 :kaocha.testable/skip]))))))
(deftest resource-deletion-and-unknown-fail-open
  (doseq [action [:resource :delete :unknown]]
    (let [{:keys [root config]} (selection-fixture)]
      (case action
        :resource (spit (io/file root "resource.edn") "{:x 2}")
        :delete (.delete (io/file root "leaf.clj"))
        :unknown (spit (io/file root "new.clj") "(ns not.tracked)"))
      (let [s (:kaocha.plugin.affected/selection (select! config))]
        (is (string? (:reason s)))
        (is (= 2 (:selected s)))))))
(deftest unknown-tracker-and-watch-focus
  (let [cfg (affected/affected-config-hook
              {:kaocha.plugin.affected/since :tracker :kaocha.watch/focus [:app.core-test/test]
               :kaocha.filter/focus [:app.core-test/test]})]
    (is (string? (get-in cfg [:kaocha.plugin.affected/decision :reason])))
    (is (empty? (:kaocha.filter/focus cfg)))))
