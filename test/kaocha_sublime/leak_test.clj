(ns kaocha-sublime.leak-test
  "kaocha.plugin.leak-detection (LOOP-LEAK-REPORT-009, LOOP-LEAK-VERDICT-010): the hooks
  driven directly, and real Kaocha runs of the fixtures under fixtures/leak/test (not
  on this repo's test path, so no suite discovers them)."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [kaocha.api :as api]
            [kaocha.classpath :as classpath]
            [kaocha.config :as config]
            [kaocha.result :as result]
            [kaocha.plugin.leak-detection :as leak]
            [kaocha-sublime.runner :as runner]))

(classpath/add-classpath "fixtures/leak/test")   ; also on the :test alias, for the cold runner

;; The plugin remembers the previous leaker in one atom; each test starts and ends clean,
;; so neither these fake leaves nor the real run around them inherit from each other.
(t/use-fixtures :each (fn [f] (reset! @#'leak/last-leak nil) (try (f) (finally (reset! @#'leak/last-leak nil)))))

(defn- leaf [id]
  {:kaocha.testable/id id :kaocha.testable/type :kaocha.type/var
   :kaocha.testable/meta {:file "demo/core_test.clj" :line 3}})

(defn- run-leaf
  "pre-test, body, post-test on a fake leaf, as Kaocha's run-testable does."
  [id body & [config extra]]
  (let [t (leak/before-test (merge (leaf id) extra))
        r (merge t (body))]
    (leak/after-test r (or config {}))))

(def ^:private started-futures (atom []))

(defn- blocking-future
  "Start a future that runs until `release` counts down; returns once it is running."
  [^java.util.concurrent.CountDownLatch release]
  (let [started (promise)
        f (future (deliver started true) (.await release))]
    (swap! started-futures conj f)
    @started))

(defn- release! [^java.util.concurrent.CountDownLatch release]
  (.countDown release)
  ;; Finish the deliberate leaks before this test returns, so the warm run around this
  ;; suite does not charge them to these test vars.
  (doseq [f @started-futures] (deref f 2000 nil))
  (reset! started-futures []))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
(deftest a-future-still-running-at-return-is-charged-to-its-test
  (let [release (java.util.concurrent.CountDownLatch. 1)]
    (try
      (let [r (run-leaf :demo.core-test/leaker #(do (blocking-future release) {:kaocha.result/pass 1}))
            record (leak/leak-record r)]
        (is (= "demo.core-test/leaker" (:test record)))
        (is (= {:submitted 1 :in-flight 1} (get-in record [:pools :send-off])))
        (is (re-find #"^clojure-agent-send-off-pool-" (:name (first (:threads record)))))
        (is (str/starts-with? (:at (first (:threads record))) "kaocha-sublime.leak-test/blocking-future")
            "the first frame of the task's own code says what is still running")
        (is (= 1 (:kaocha.result/pass r)) "the leaking test's own verdict is unchanged")
        (is (number? (::leak/check-nanos r))))
      (finally (release! release)))))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
(deftest a-drained-future-is-not-a-leak
  (let [r (run-leaf :demo.core-test/drained #(do @(future (Thread/sleep 20) 1) {:kaocha.result/pass 1}))]
    (is (nil? (::leak/leak r)))))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
(deftest an-idle-pool-worker-left-by-an-earlier-test-is-not-a-leak
  @(future 1)                            ; a worker now lingers idle for up to 60 s
  (let [r (run-leaf :demo.core-test/quiet (constantly {:kaocha.result/pass 1}))]
    (is (nil? (::leak/leak r)))))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
(deftest allowlisted-thread-births-are-counted-not-reported
  (let [release (java.util.concurrent.CountDownLatch. 1)
        threads (atom [])
        start! (fn [name]
                 (let [th (doto (Thread. #(.await release) ^String name) (.setDaemon true) .start)]
                   (swap! threads conj th)
                   ;; Until it parks, a new thread reads RUNNABLE.
                   (while (not= Thread$State/WAITING (.getState th)) (Thread/yield))))]
    (try
      (let [r (run-leaf :demo.core-test/threads
                        #(do (start! "AWT-EventQueue-fake") (start! "my-poller") (start! "x-allowed")
                             {:kaocha.result/pass 1})
                        {::leak/allow-threads ["^x-allowed$"]})
            record (leak/leak-record r)]
        (is (= ["my-poller"] (mapv :name (:new-threads record))) "only the unexplained birth is a leak")
        (is (= "WAITING" (:state (first (:new-threads record)))))
        (is (= 2 (:allowed record)) "the default and the project allowlist are counted, never dropped"))
      (finally (.countDown release) (doseq [^Thread th @threads] (.join th 2000))))))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
(deftest an-opted-out-test-is-counted-not-charged
  (let [release (java.util.concurrent.CountDownLatch. 1)]
    (try
      (let [r (run-leaf :demo.core-test/service #(do (blocking-future release) {:kaocha.result/pass 1})
                        {} {:kaocha.testable/meta {:kaocha.leak/allow true}})
            details (runner/leak-details {:kaocha.result/tests [r]})]
        (is (true? (:opted-out (leak/leak-record r))))
        (is (= 0 (:leak-count details)))
        (is (= 1 (:leaks-allowed details))))
      (finally (release! release)))))

;; INTENT-TEST: LOOP-LEAK-VERDICT-010
(deftest a-failing-test-that-inherited-in-flight-work-is-marked-contaminated
  (let [release (java.util.concurrent.CountDownLatch. 1)]
    (try
      (run-leaf :demo.core-test/leaker #(do (blocking-future release) {:kaocha.result/pass 1}))
      (let [victim (run-leaf :demo.core-test/victim (constantly {:kaocha.result/fail 1}))
            passer (run-leaf :demo.core-test/passer (constantly {:kaocha.result/pass 1}))]
        (is (= {:from :demo.core-test/leaker :in-flight 1} (::leak/inherited victim)))
        (is (= :demo.core-test/leaker (::leak/contaminated-by victim)))
        (is (::leak/inherited passer) "a passing inheritor is marked as inheriting...")
        (is (nil? (::leak/contaminated-by passer)) "...but not contaminated"))
      (release! release)
      (let [later (run-leaf :demo.core-test/later (constantly {:kaocha.result/fail 1}))]
        (is (nil? (::leak/inherited later)) "drained before the next test started: nothing inherited"))
      (finally (release! release)))))

;; INTENT-TEST: LOOP-LEAK-VERDICT-010
(deftest fail-on-leak-adds-one-fail-event-and-nothing-else-changes
  (let [release (java.util.concurrent.CountDownLatch. 1)
        reported (atom [])]
    (try
      (let [[strict clean]
            (binding [t/report #(swap! reported conj %)]
              [(run-leaf :demo.core-test/leaker
                         #(do (blocking-future release) {:kaocha.result/pass 2 :kaocha.result/fail 0})
                         {::leak/fail-on-leak true})
               (run-leaf :demo.core-test/clean (constantly {:kaocha.result/pass 1})
                         {::leak/fail-on-leak true})])]
          (is (= 1 (:kaocha.result/fail strict)))
          (is (= 2 (:kaocha.result/pass strict)) "pass counts unchanged")
          (is (= [:fail] (mapv :type (:kaocha.testable/events strict))))
          (is (= ["demo/core_test.clj" 3] ((juxt :file :line) (first (:kaocha.testable/events strict)))))
          (is (= [:fail] (mapv :type @reported)) "reported once, so streams and receipts agree")
          (is (= {:kaocha.result/pass 1} (select-keys clean [:kaocha.result/pass :kaocha.result/fail]))
              "a test without a leak is untouched"))
      (finally (release! release)))))

;; ---------------------------------------------------------------- real Kaocha runs

(defn- kaocha-run [ns-kw & [extra]]
  (let [cfg (merge (config/merge-config
                    (binding [*out* (java.io.StringWriter.)] (config/load-config nil))
                    {:kaocha/tests [{:kaocha.testable/id :leaks :kaocha.testable/type :kaocha.type/clojure.test
                                     :kaocha/ns-patterns ["-test$"] :kaocha/source-paths []
                                     :kaocha/test-paths ["fixtures/leak/test"]}]
                     :kaocha/plugins [:kaocha.plugin/filter :kaocha.plugin/capture-output :kaocha.plugin/leak-detection]
                     :kaocha/reporter ['kaocha.report/documentation]
                     :kaocha/color? false
                     :kaocha/fail-fast? false
                     ;; The field case is an order: the leaker runs first (Kaocha sorts vars by name).
                     :kaocha.plugin.randomize/randomize? false
                     :kaocha.filter/focus [ns-kw]})
                   extra)
        result (atom nil)]
    ;; Only the nested run gets fresh counters (Kaocha diffs against the enclosing ones);
    ;; this test's own assertions stay outside the binding.
    (with-out-str (binding [t/*test-out* *out* t/*report-counters* nil] (reset! result (api/run cfg))))
    @result))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
;; INTENT-TEST: LOOP-LEAK-VERDICT-010
(deftest the-field-case-names-the-leaker-beside-the-victims-failure
  (require 'leak-sample.race-test)
  (reset! @(resolve 'leak-sample.race-test/side) :clean)
  (let [r (kaocha-run :leak-sample.race-test)
        leaks (runner/leak-details r)
        failures (:failures (runner/failure-details r))]
    (is (= 1 (:leak-count leaks)))
    (is (= "leak-sample.race-test/a-leaker" (:test (first (:leaks leaks)))) "the culprit is named")
    (is (= {:submitted 1 :in-flight 1} (get-in (first (:leaks leaks)) [:pools :send-off])))
    (is (str/includes? (:at (first (:threads (first (:leaks leaks))))) "race_test.clj")
        "with the line of the work still running")
    (is (= 2 (:leak-checked leaks)))
    (is (= [["leak-sample.race-test/b-victim" "leak-sample.race-test/a-leaker"]]
           (mapv (juxt :test :contaminated-by) failures))
        "the victim's failure carries the suspect")
    (is (= {:tests 2 :fail 1} (select-keys (let [t (result/totals (:kaocha.result/tests r))]
                                             {:tests (:kaocha.result/count t) :fail (:kaocha.result/fail t)})
                                           [:tests :fail]))
        "default mode: counts are the tests' own")))

;; INTENT-TEST: LOOP-LEAK-VERDICT-010
(deftest the-field-case-in-strict-mode-fails-the-leaker
  (require 'leak-sample.race-test)
  (reset! @(resolve 'leak-sample.race-test/side) :clean)
  (let [r (kaocha-run :leak-sample.race-test {::leak/fail-on-leak true})
        failures (:failures (runner/failure-details r))]
    (is (= ["leak-sample.race-test/a-leaker" "leak-sample.race-test/b-victim"] (mapv :test failures)))
    (is (str/starts-with? (:message (first failures)) "background work still running when the test returned: send-off: 1 in flight of 1 submitted; running clojure-agent-send-off-pool-"))
    (is (not-any? #(contains? (first failures) %) [:expr :expected :actual]) "no assertion form to show")))

;; INTENT-TEST: LOOP-LEAK-REPORT-009
(deftest a-drain-fixture-runs-inside-the-window
  (let [r (kaocha-run :leak-sample.drained-test)]
    (is (= 0 (:leak-count (runner/leak-details r))) "the :each fixture drained it before post-test")
    (is (= 1 (:leak-checked (runner/leak-details r))))))
