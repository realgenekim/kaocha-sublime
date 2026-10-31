(ns kaocha-sublime.runner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaocha-sublime.runner :as runner]))

(defn- var-node
  "A test var node shaped like Kaocha's result tree."
  [id events & [output]]
  (cond-> {:kaocha.testable/id id
           :kaocha.testable/type :kaocha.type/var
           :kaocha.testable/meta {:file "kaocha_sublime/runner_test.clj" :line 1}
           :kaocha.testable/events events}
    output (assoc :kaocha.plugin.capture-output/output output)))

(defn- result [& vars]
  {:kaocha.result/tests
   [{:kaocha.testable/id :unit
     :kaocha.result/tests [{:kaocha.testable/id :demo.core-test
                            :kaocha.testable/type :kaocha.type/ns
                            :kaocha.result/tests (vec vars)}]}]})

(def equality-fail
  ;; What clojure.test reports for (is (= {:a 1} {:a 2}) "maps match") in (testing "outer" (testing "inner" ...)).
  {:type :fail :message "maps match" :file "test/kaocha_sublime/runner_test.clj" :line 7
   :testing-contexts (list "inner" "outer")
   :expected '(= {:a 1} {:a 2}) :actual (list 'not (list '= {:a 1} {:a 2}))})

;; INTENT-TEST: LOOP-FAILURE-DETAILS-001
(deftest each-failure-names-its-var-location-message-values-and-output
  (let [{:keys [failures failures-omitted]}
        (runner/failure-details
         (result (var-node :demo.core-test/passes [{:type :pass}] "noise\n")
                 (var-node :demo.core-test/adds [{:type :pass} equality-fail] "timing: 42 ms\nwarn\n")))]
    (is (= 0 failures-omitted))
    (is (= [{:kind "fail" :test "demo.core-test/adds"
             :at "test/kaocha_sublime/runner_test.clj:7"
             :file "test/kaocha_sublime/runner_test.clj" :line 7
             :message "maps match" :context "outer > inner"
             :expr "(= {:a 1} {:a 2})" :expected "{:a 1}" :actual "{:a 2}"
             :output "timing: 42 ms\nwarn\n"}]
           failures)
        "only failing assertions appear; equality shows the evaluated values")
    (is (= [:kind :test :at :file :line :message :context :expr :expected :actual :output]
           (keys (first failures)))
        "a stable, readable key order")))

;; INTENT-TEST: LOOP-FAILURE-DETAILS-001
(deftest an-error-shows-class-message-data-cause-and-the-throwing-frame
  (let [t (ex-info "boom" {:x 1} (IllegalStateException. "root cause"))
        entry (first (:failures (runner/failure-details
                                 (result (var-node :demo.core-test/throws
                                                   [{:type :error :message "Uncaught exception, not in assertion."
                                                     :expected nil :actual t}])))))]
    (is (= "error" (:kind entry)))
    (is (str/starts-with? (:actual entry)
                          "clojure.lang.ExceptionInfo: boom {:x 1} | caused by java.lang.IllegalStateException: root cause"))
    (is (re-find #"\| at kaocha-sublime\.runner-test/" (:actual entry))
        "the first frame outside clojure/java/kaocha is the test code, demunged")
    (is (not (contains? entry :expr)) "an uncaught error has no assertion form")
    (is (= "test/kaocha_sublime/runner_test.clj:1" (:at entry))
        "with no event location, the var's classpath file resolves under the root")))

;; INTENT-TEST: LOOP-FAILURE-DETAILS-001
(deftest failure-details-are-bounded
  (let [huge (apply str (repeat 5000 "x"))
        output (str (apply str (repeat 3000 "-")) "LAST LINE\n")
        fails (for [i (range 13)]
                (var-node (keyword "demo.core-test" (str "t" i))
                          [{:type :fail :message huge :expected (list '= 1 '(range))
                            :actual (list 'not (list '= huge (range)))}]
                          output))
        {:keys [failures failures-omitted]} (runner/failure-details (apply result fails))
        entry (first failures)]
    (is (= runner/failure-limit (count failures)))
    (is (= (- 13 runner/failure-limit) failures-omitted))
    (testing "values are cut at value-limit, an infinite seq does not hang"
      (doseq [k [:message :expected :actual]]
        (is (<= (count (get entry k)) (+ runner/value-limit 3)) (name k)))
      (is (str/ends-with? (:expected entry) "...") "the 5000-char value is cut")
      (is (str/ends-with? (:actual entry) "39 ...)") "an infinite seq prints its first 40 items"))
    (testing "output keeps its END, where the last thing printed is"
      (is (<= (count (:output entry)) (+ runner/output-limit 3)))
      (is (str/ends-with? (:output entry) "LAST LINE\n"))
      (is (str/starts-with? (:output entry) "...")))
    (is (str/starts-with? (runner/bounded-pr-str (reify Object (toString [_] (throw (Exception. "no")))))
                          "<unprintable ")
        "a value whose printing throws never breaks the receipt")))

;; INTENT-TEST: LOOP-FAILURE-DETAILS-001
(deftest a-passing-tree-has-no-failures
  (is (= {:failures [] :failures-omitted 0}
         (runner/failure-details (result (var-node :demo.core-test/ok [{:type :pass}]))))))

;; INTENT-TEST: LOOP-VAR-FOCUS-002
(deftest a-var-target-reloads-its-namespace-and-a-missing-var-is-named
  (let [r (runner/check! ["kaocha-sublime.runner-fixture/absent"
                          "kaocha-sublime.runner-fixture/not-a-test"
                          "kaocha-sublime.runner-fixture/present"])]
    (is (= "fail" (:status r)))
    (is (= "focus" (:stage r)) "refused before any test runs, after a complete reload")
    (is (= "no such test var: kaocha-sublime.runner-fixture/absent, kaocha-sublime.runner-fixture/not-a-test"
           (:error r))
        "a non-test var is as wrong as an absent one; a real test var is not named")
    (is (= ["kaocha-sublime.runner-fixture"] (:reload-targets r))
        "the var's namespace is the reload root, exactly as if the namespace were named")
    (is (nil? (:counts r)) "nothing ran")))

;; INTENT-TEST: LOOP-FAILURE-DETAILS-001
(deftest kaocha-failure-types-and-bare-stack-files-still-explain-themselves
  (let [[zero error] (:failures (runner/failure-details
                                 (result (var-node :demo.core-test/empty
                                                   [{:type :kaocha.type.var/zero-assertions}])
                                         (var-node :demo.core-test/throws
                                                   [{:type :error :file "runner.clj" :line 9
                                                     :actual (Exception. "x")}]))))]
    (is (= "Test ran without assertions. Did you forget an (is ...)?" (:message zero)))
    (is (= "src/kaocha_sublime/runner.clj:9" (:at error))
        "a bare stack-frame file name resolves to the one source file with that name")))
