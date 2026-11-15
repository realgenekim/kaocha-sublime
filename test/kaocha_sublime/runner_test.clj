(ns kaocha-sublime.runner-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaocha-sublime.reload :as reload]
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

;; INTENT-TEST: LOOP-RELOAD-FAILURE-005
(deftest a-reload-syntax-error-is-reported-and-the-next-reload-repairs-it
  ;; A transient source file under test/ (deleted in finally, so a warm check's
  ;; before/after source fingerprint is unchanged). The target names an absent var, so
  ;; each check stops at the focus refusal after a complete reload: no nested Kaocha run.
  (let [f (io/file "test/kaocha_sublime/reload_fixture.clj")
        target ["kaocha-sublime.reload-fixture/absent"]
        value #(deref (resolve 'kaocha-sublime.reload-fixture/value))]
    (try
      (spit f "(ns kaocha-sublime.reload-fixture)\n(def value 1)\n")
      (is (= "focus" (:stage (runner/check! target))) "the clean namespace loads")
      (is (= 1 (value)))
      (spit f "(ns kaocha-sublime.reload-fixture)\n(def value 2)\n(def broken (\n")
      (let [r (runner/check! target)
            failure (:reload-failure r)]
        (is (= "fail" (:status r)) "a failed reload is never a pass")
        (is (= "reload" (:stage r)))
        (is (nil? (:counts r)) "nothing ran")
        (is (true? (:jvm-usable r)) "one bad namespace does not make the JVM unusable")
        (is (= {:namespace "kaocha-sublime.reload-fixture"
                :at "test/kaocha_sublime/reload_fixture.clj:4"
                :file "test/kaocha_sublime/reload_fixture.clj" :line 4 :phase "read-source"}
               (select-keys failure [:namespace :at :file :line :phase])))
        (is (str/includes? (:message failure) "EOF while reading"))
        (is (= "reload of kaocha-sublime.reload-fixture failed at test/kaocha_sublime/reload_fixture.clj:4: "
               (subs (:error r) 0 (- (count (:error r)) (count (:message failure))))))
        (is (= [{:kind "load" :test "kaocha-sublime.reload-fixture"
                 :at "test/kaocha_sublime/reload_fixture.clj:4"}]
               (mapv #(select-keys % [:kind :test :at]) (:failures r))))
        (is (= ["kaocha-sublime.reload-fixture"] (:not-reloaded r))))
      (spit f "(ns kaocha-sublime.reload-fixture)\n(def value 3)\n")
      (let [r (runner/check! target)]
        (is (= "focus" (:stage r)) "the next check reloads the closure again")
        (is (nil? (:reload-failure r)))
        (is (= 3 (value)) "and the stale namespace is repaired in the same JVM"))
      (finally
        (.delete f)
        (remove-ns 'kaocha-sublime.reload-fixture)))))

;; INTENT-TEST: LOOP-RELOAD-FAILURE-005
(deftest only-a-vm-error-makes-the-jvm-unusable
  (is (runner/jvm-usable? (ex-info "Syntax error" {} (RuntimeException. "EOF"))))
  (is (not (runner/jvm-usable? (RuntimeException. "load" (OutOfMemoryError. "heap")))))
  (is (not (runner/jvm-usable? (StackOverflowError.)))))

;; ---------------------------------------------------------------- ks-216 timings

(defn- timed-node [id nanos counts & [skip?]]
  (cond-> (merge {:kaocha.testable/id id :kaocha.testable/type :kaocha.type/var}
                 (when nanos {:kaocha.plugin.profiling/duration nanos})
                 counts)
    skip? (assoc :kaocha.testable/skip true)))

;; INTENT-TEST: LOOP-RECEIPT-LOAD-SLOWEST-006
(deftest test-timings-name-each-var-with-its-duration-and-outcome
  (let [tree (result (timed-node :demo.core-test/quick 1234567 {:kaocha.result/pass 1})
                     (timed-node :demo.core-test/slow 812400000 {:kaocha.result/pass 1 :kaocha.result/fail 1})
                     (timed-node :demo.core-test/boom 5000000 {:kaocha.result/error 1})
                     (timed-node :demo.core-test/later 2000000 {:kaocha.result/pending 1})
                     (timed-node :demo.core-test/skipped 9000000000 {} true)
                     (timed-node :demo.core-test/untimed nil {:kaocha.result/pass 1}))
        timings (runner/test-timings tree)]
    (is (= [{:test "demo.core-test/quick" :ms 1.2 :outcome "pass"}
            {:test "demo.core-test/slow" :ms 812.4 :outcome "fail"}
            {:test "demo.core-test/boom" :ms 5.0 :outcome "error"}
            {:test "demo.core-test/later" :ms 2.0 :outcome "pending"}]
           timings)
        "run order, ms to 0.1; skipped and untimed vars are left out")
    (is (= ["demo.core-test/slow" "demo.core-test/boom"]
           (mapv :test (runner/slowest timings 2))))
    (is (= 4 (count (runner/slowest timings runner/slowest-default))) "fewer vars than N: all of them")
    (is (= [] (runner/slowest timings 0)))))

;; ---------------------------------------------------------------- ks-wzy perf mode

;; INTENT-TEST: LOOP-PERF-MODE-008
(deftest perf-vars-are-the-perf-tagged-test-vars-of-the-targets
  (require 'kaocha-sublime.runner-fixture :reload)
  (is (= '[kaocha-sublime.runner-fixture/timed]
         (runner/perf-vars '[kaocha-sublime.runner-fixture])) "a namespace: its ^:perf deftests only")
  (is (= '[kaocha-sublime.runner-fixture/timed]
         (runner/perf-vars '[kaocha-sublime.runner-fixture/timed])) "a ^:perf var target")
  (is (= [] (runner/perf-vars '[kaocha-sublime.runner-fixture/present])) "an untagged var target")
  (is (= [] (runner/perf-vars '[kaocha-sublime.runner-fixture/not-a-test])) "a plain def"))

;; INTENT-TEST: LOOP-PERF-MODE-008
(deftest a-perf-run-unskips-perf-at-the-top-level-and-in-every-suite
  (let [probe-unskip (requiring-resolve 'kaocha-sublime.probe/unskip-meta)
        cfg {:kaocha.filter/skip-meta [:perf :slow]
             :kaocha/tests [{:kaocha.testable/id :unit :kaocha.filter/skip-meta [:perf]}
                            {:kaocha.testable/id :perf :kaocha.filter/focus-meta [:perf]}]}]
    (is (= {:kaocha.filter/skip-meta [:slow]
            :kaocha/tests [{:kaocha.testable/id :unit :kaocha.filter/skip-meta []}
                           {:kaocha.testable/id :perf :kaocha.filter/focus-meta [:perf]}]}
           (probe-unskip cfg [:perf])))
    (is (= cfg (probe-unskip cfg nil)) "an ordinary check keeps tests.edn's skips")))

;; INTENT-TEST: LOOP-PERF-MODE-008
(deftest with-no-namespace-named-perf-mode-takes-the-default-suites-test-namespaces
  (let [test-namespaces @#'runner/test-namespaces
        filemap {(io/file "test/kaocha_sublime/runner_test.clj") 'kaocha-sublime.runner-test
                 (io/file "test/kaocha_sublime/driver.clj") 'kaocha-sublime.driver
                 (io/file "src/kaocha_sublime/runner.clj") 'kaocha-sublime.runner}]
    (is (= '[kaocha-sublime.runner-test]
           (binding [*out* (java.io.StringWriter.) *err* (java.io.StringWriter.)] (test-namespaces filemap))))))

;; INTENT-TEST: LOOP-PERF-MODE-008
(deftest a-perf-check-without-perf-tests-is-refused-by-name
  ;; The refusal comes after a complete reload and runs nothing: no nested Kaocha run.
  (let [r (runner/check! ["kaocha-sublime.runner-fixture/present"] {:perf true})]
    (is (= "fail" (:status r)))
    (is (= "focus" (:stage r)))
    (is (= "no ^:perf test vars in: kaocha-sublime.runner-fixture/present" (:error r)))
    (is (= [] (:perf-selected r)))
    (is (= ["kaocha-sublime.runner-fixture"] (:reload-targets r)))))

;; ---------------------------------------------------------------- ks-mn0 repeat

;; INTENT-TEST: LOOP-REPEAT-007
(deftest a-repeat-run-can-skip-the-reload
  (let [r (runner/check! ["kaocha-sublime.runner-fixture/absent"] {:reload false})]
    (is (= "focus" (:stage r)))
    (is (false? (:reloaded r)))
    (is (= [] (:reload-targets r)) "nothing reloaded"))
  (let [r (runner/check! ["kaocha-sublime.runner-fixture/absent"])]
    (is (not (contains? r :reloaded)) "the default reloads, as before")
    (is (= ["kaocha-sublime.runner-fixture"] (:reload-targets r)))))

;; ---------------------------------------------------------------- reload unmaps deleted definitions

(def ^:private unmap-header
  "(ns kaocha-sublime.unmap-fixture (:require [clojure.test :refer [deftest is]]))\n(defonce state (atom 0))\n(defprotocol Shape (area [s]))\n(defrecord Sq [n] Shape (area [_] (* n n)))\n")

;; INTENT-TEST: LOOP-RELOAD-UNMAP-013
(deftest a-definition-deleted-from-its-file-is-unmapped-and-defonce-state-survives
  ;; Transient file under test/ (deleted in finally); the absent-var target stops each check
  ;; at the focus refusal after a complete reload, so no nested Kaocha run.
  (let [f (io/file "test/kaocha_sublime/unmap_fixture.clj")
        target ["kaocha-sublime.unmap-fixture/absent"]
        fixture #(ns-resolve 'kaocha-sublime.unmap-fixture %)]
    (try
      (spit f (str unmap-header "(defn gone [] :old)\n(defn kept [] :kept)\n(deftest deleted-test (is true))\n(defn start! [] (def client :connected))\n"))
      (is (= "focus" (:stage (runner/check! target))))
      (is (= :old ((fixture 'gone))))
      ((fixture 'start!))
      (swap! @(fixture 'state) inc)
      (let [state-atom @(fixture 'state)
            state-var (fixture 'state)
            shape-var (fixture 'Shape)
            kept-var (fixture 'kept)]
        (spit f (str unmap-header "(defn kept [] :kept-2)\n(defn start! [] (def client :connected))\n"))
        (let [r (runner/check! target)]
          (is (= "focus" (:stage r)) "the reload succeeded")
          (is (some #(and (= "client" (:var %)) (= "still-in-file" (:reason %))) (:kept r))
              "the kept reason reaches the public receipt")
          (is (not-any? #(= "client" (:var %)) (get-in r [:stale :defs]))
              "a retained definition is not a stale-definition warning")
          (is (nil? (fixture 'gone)) "the deleted function's var is gone")
          (is (thrown? Exception (eval '(kaocha-sublime.unmap-fixture/gone)))
              "calling it fails, as in a cold JVM")
          (is (nil? (fixture 'deleted-test)) "a deleted test var is gone too")
          (is (= [{:ns "kaocha-sublime.unmap-fixture" :var "deleted-test"
                   :at "test/kaocha_sublime/unmap_fixture.clj:7"}
                  {:ns "kaocha-sublime.unmap-fixture" :var "gone"
                   :at "test/kaocha_sublime/unmap_fixture.clj:5"}]
                 (sort-by :var (:unmapped r)))
              "each unmapped var with where the file used to define it")
          (is (identical? state-var (fixture 'state)) "never remove-ns: the defonce var is the same object")
          (is (identical? state-atom @(fixture 'state)) "and keeps its state")
          (is (= 1 @@(fixture 'state)))
          (is (identical? shape-var (fixture 'Shape)) "the protocol var keeps its identity")
          (is (identical? kept-var (fixture 'kept)) "a redefined var is the same Var")
          (is (= :kept-2 ((fixture 'kept))) "with the new body")
          (is (= 9 ((fixture 'area) ((fixture '->Sq) 3))) "the record still satisfies the protocol"))
        (let [r (runner/check! target)]
          (is (not (contains? r :unmapped)) "nothing more to unmap on the next check")))
      (finally
        (.delete f)
        (remove-ns 'kaocha-sublime.unmap-fixture)))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-013
(deftest only-vars-the-file-defined-are-unmapped
  (let [reload-ns (create-ns 'kaocha-sublime.unmap-unit)
        source (.getCanonicalFile (java.io.File/createTempFile "unmap-unit-" ".clj"))]
    (try
      (spit source "")
      ;; What `def` leaves: a var whose :file is the resource path of the file that defined it.
      (reset-meta! (intern reload-ns 'from-file 1) {:file (.getPath source) :line 3})
      (reset-meta! (intern reload-ns 'from-other 2) {:file "kaocha_sublime/other_part.clj" :line 9})
      (intern reload-ns 'runtime 3)
      (let [before (reload/interned-meta 'kaocha-sublime.unmap-unit)]
        (is (= #{'from-file 'from-other 'runtime} (set (keys before))))
        ;; A "reload" that redefines nothing:
        (let [r (reload/unmap-stale! 'kaocha-sublime.unmap-unit before source)]
          (is (= '[from-file] (mapv :var (:unmapped r))) "the file's own definition is unmapped")
          (is (= '[from-other] (mapv :var (:kept r))) "a var another file defined is kept and listed")
          (is (= 1 (:dynamic r)) "a runtime intern without :file is counted, never unmapped")
          (is (nil? (ns-resolve reload-ns 'from-file)))
          (is (some? (ns-resolve reload-ns 'from-other)))
          (is (some? (ns-resolve reload-ns 'runtime)))))
      (is (nil? (reload/interned-meta 'kaocha-sublime.no-such-namespace)))
      (finally (.delete source) (remove-ns 'kaocha-sublime.unmap-unit)))))

(defn- with-source [text f]
  (let [source (.getCanonicalFile (java.io.File/createTempFile "reload-014-" ".clj"))
        ns-sym (gensym "reload-014-")
        ns (create-ns ns-sym)]
    (try
      (binding [*ns* ns]
        (clojure.core/refer 'clojure.core)
        (spit source text)
        (load-file (.getPath source))
        (f ns-sym source))
      (finally (.delete source) (remove-ns ns-sym)))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-def-inside-a-function-body-survives-a-reload
  (with-source "(defn start! [] (def ^:private client :connected))\n(defonce state (atom 1))"
    (fn [ns-sym source]
      ;; Compilation interns client even though start! has never run.
      (let [client (ns-resolve ns-sym 'client)
            state @(ns-resolve ns-sym 'state)
            before (reload/interned-meta ns-sym)]
        (load-file (.getPath source))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (identical? client (ns-resolve ns-sym 'client)))
          (is (empty? (:unmapped r)))
          (is (identical? state @(ns-resolve ns-sym 'state))))
        ((ns-resolve ns-sym 'start!))
        (let [before (reload/interned-meta ns-sym)]
          (load-file (.getPath source))
          (let [r (reload/unmap-stale! ns-sym before source)]
            (is (some #(and (= 'client (:var %)) (= :still-in-file (:reason %))) (:kept r))))
          (is (= :connected @(ns-resolve ns-sym 'client))))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-deleted-definition-is-still-unmapped
  (with-source "(defn gone [] :old)"
    (fn [ns-sym source]
      (let [before (reload/interned-meta ns-sym)]
        (spit source "")
        (is (= '[gone] (mapv :var (:unmapped (reload/unmap-stale! ns-sym before source)))))
        (is (nil? (ns-resolve ns-sym 'gone)))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-mentioned-but-undefined-symbol-is-still-unmapped
  (with-source "(def gone 1)"
    (fn [ns-sym source]
      (let [before (reload/interned-meta ns-sym)]
        (spit source "(comment gone)\n'(gone)\n::keyword")
        (is (= '[gone] (mapv :var (:unmapped (reload/unmap-stale! ns-sym before source)))))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-definition-moved-into-a-comment-or-quoted-data-is-unmapped
  ;; The stale-pass this rule exists to refuse: commenting a test out leaves the old
  ;; test executable in the warm image, where a cold JVM has no such test at all.
  (with-source "(clojure.test/deftest deleted-test (clojure.test/is true))
(def gone 1)
(defn also-gone [] :old)"
    (fn [ns-sym source]
      (let [before (reload/interned-meta ns-sym)]
        (is (= '[also-gone deleted-test gone] (sort (keys (select-keys before '[deleted-test gone also-gone]))))
            "all three were loaded")
        (spit source (str "(comment (clojure.test/deftest deleted-test (clojure.test/is true)))\n"
                          "'(def gone 1)\n"
                          "(clojure.core/comment (defn also-gone [] :old))\n"
                          "(def still-here 1)"))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (= '[also-gone deleted-test gone] (sort (mapv :var (:unmapped r))))
              "a comment form and quoted data define nothing, at any depth")
          (is (empty? (:kept r)))
          (is (every? #(nil? (ns-resolve ns-sym %)) '[deleted-test gone also-gone])))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-form-that-looks-like-the-readers-eof-sentinel-does-not-end-the-scan
  ;; ::eof read in kaocha-sublime.reload is the keyword below, an ordinary form any
  ;; file may contain; as a sentinel it would stop the scan at line 1 and report every
  ;; definition after it as deleted.
  (with-source "(defn start! [] (def client :connected))"
    (fn [ns-sym source]
      ((ns-resolve ns-sym 'start!))      ; so the inner def's var carries this file's :file
      (let [before (reload/interned-meta ns-sym)]
        (spit source ":kaocha-sublime.reload/eof\n(defn start! [] (def client :connected))\n")
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (empty? (:unmapped r)) "the scan reads past it and sees the whole file")
          (is (= '[client start!] (sort (mapv :var (:kept r)))))
          (is (every? #(= :still-in-file (:reason %)) (:kept r))))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest only-clojure-cores-comment-is-a-comment-form
  (with-source "(defn start! [] (def client :connected))\n(def gone 1)"
    (fn [ns-sym source]
      ((ns-resolve ns-sym 'start!))      ; so the inner def's var carries this file's :file
      (let [before (reload/interned-meta ns-sym)]
        (spit source (str "(helper/comment (defn start! [] (def client :connected)))\n"
                          "(clojure.core/comment (def gone 1))\n"))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (= '[gone] (mapv :var (:unmapped r)))
              "clojure.core/comment is the comment form, by its own name")
          (is (= '[client start!] (sort (mapv :var (:kept r))))
              "helper/comment is an ordinary function that evaluates its arguments")
          (is (every? #(= :still-in-file (:reason %)) (:kept r))))
        ;; An ns form that aliases clojure.core: through that alias it IS the comment form.
        (spit source (str "(ns " ns-sym " (:require [clojure.core :as core]))\n"
                          "(core/comment (defn start! [] (def client :connected)))\n"))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (= '[client start!] (sort (mapv :var (:unmapped r))))
              "an alias the file gives clojure.core resolves to it"))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-locally-shadowed-comment-runs-its-body-and-keeps-the-definition
  ;; Review round 4 (codex): (let [comment identity] (comment (def client ...))) runs the
  ;; def. Treating that bare comment as clojure.core's unmapped client after start! ran,
  ;; and the next dependent reload failed with No such var.
  (let [src "(defn start! [] (let [comment identity] (comment (def client :connected))))"]
    (with-source src
      (fn [ns-sym source]
        ((ns-resolve ns-sym 'start!))
        (is (= :connected @(ns-resolve ns-sym 'client)) "start! ran the def")
        (let [before (reload/interned-meta ns-sym)]
          (load-file (.getPath source))
          (let [r (reload/unmap-stale! ns-sym before source)]
            (is (empty? (:unmapped r)))
            (is (some #(and (= 'client (:var %)) (= :still-in-file (:reason %))) (:kept r))))
          (is (= :connected @(ns-resolve ns-sym 'client)) "client survives the reload"))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-file-that-shadows-comment-keeps-the-definitions-inside-it
  (with-source "(defn start! [] (def client :connected))\n(def gone 1)"
    (fn [ns-sym source]
      ((ns-resolve ns-sym 'start!))
      (let [before (reload/interned-meta ns-sym)]
        ;; :refer-clojure :exclude [comment] plus the file's own comment function.
        (spit source (str "(ns " ns-sym " (:refer-clojure :exclude [comment]))\n"
                          "(defn comment [& xs] xs)\n"
                          "(comment (defn start! [] (def client :connected)))\n"
                          "(clojure.core/comment (def gone 1))\n"))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (= '[gone] (mapv :var (:unmapped r)))
              "clojure.core/comment by its full name is still the comment form")
          (is (= '[client start!] (sort (mapv :var (:kept r))))
              "the bare comment is the file's own function, so its body defines"))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest an-unreadable-file-unmaps-nothing
  (with-source "(def one 1) (def two 2)"
    (fn [ns-sym source]
      (let [before (reload/interned-meta ns-sym)
            err (java.io.StringWriter.)]
        (spit source "(def broken")
        (let [r (binding [*err* err] (reload/unmap-stale! ns-sym before source))]
          (is (empty? (:unmapped r)))
          (is (every? #(ns-resolve ns-sym %) '[one two]))
          (is (= [:unreadable-file :unreadable-file] (mapv :reason (:kept r))))
          (is (= [(str "reload: cannot read source forms for " ns-sym "; unmapping nothing")]
                 (str/split-lines (str err)))
              "one warning for the namespace, saying what it could not do"))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-file-that-fails-to-read-with-an-error-unmaps-nothing
  ;; A form nested past the stack (measured: 100k nested lists) throws
  ;; StackOverflowError, not an Exception, and an Error while reading is as little
  ;; evidence of a deletion as a reader exception is. The Error is injected rather than
  ;; provoked: really overflowing the stack of a warm JVM other repos share, on every
  ;; suite run, costs more than this line of coverage is worth.
  (with-source "(def one 1)"
    (fn [ns-sym source]
      (let [before (reload/interned-meta ns-sym)
            err (java.io.StringWriter.)
            reader io/reader]
        (let [r (binding [*err* err]
                  (with-redefs [io/reader (fn [& args]
                                            (if (= (seq args) [source])
                                              (throw (StackOverflowError.))
                                              (apply reader args)))]
                    (reload/unmap-stale! ns-sym before source)))]
          (is (empty? (:unmapped r)))
          (is (= [:unreadable-file] (mapv :reason (:kept r))))
          (is (some? (ns-resolve ns-sym 'one)))
          (is (= [(str "reload: cannot read source forms for " ns-sym "; unmapping nothing")]
                 (str/split-lines (str err)))))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest source-definitions-are-read-once-and-only-for-candidates
  (with-source ""
    (fn [ns-sym source]
      (let [names '[a b c d e f g h i j k l]
            reader io/reader
            reads (atom 0)]
        (doseq [sym names]
          (reset-meta! (intern (find-ns ns-sym) sym 1) {:file (.getPath source)}))
        (spit source "[{:nested (fn [] (def ^String a nil))}]\n(defonce b nil)\n(defn c [])\n(defn- d [])\n(defmacro e [])\n(defmulti f identity)\n(declare g h)\n#?(:clj (deftest i))\n(defprotocol j)\n(defrecord k [])\n(deftype l [])\n::qualified")
        (with-redefs [io/reader (fn [& args] (swap! reads inc) (apply reader args))]
          (reload/unmap-stale! ns-sym {} source)
          (is (zero? @reads) "no source read without candidates")
          (let [r (reload/unmap-stale! ns-sym (reload/interned-meta ns-sym) source)]
            (is (= 1 @reads) "one read shared by all candidates")
            (is (empty? (:unmapped r)))
            (is (= (set names) (set (map :var (:kept r)))))
            (is (every? #(= :still-in-file (:reason %)) (:kept r)))))
        (spit source "#=(throw (Exception. \"must never evaluate\"))")
        (let [err (java.io.StringWriter.)
              r (binding [*err* err]
                  (reload/unmap-stale! ns-sym (reload/interned-meta ns-sym) source))]
          (is (empty? (:unmapped r)) "read-eval is disabled")
          (is (= [(str "reload: cannot read source forms for " ns-sym "; unmapping nothing")]
                 (str/split-lines (str err)))
              "the refused read is the one warning on stderr"))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest macro-made-vars-survive-a-reload
  (with-source "(defrecord Foo [x])
(deftype Bar [x])
(defprotocol Ping (ping [this]))
(defmacro def-state [n] `(def ~n (atom 1)))
(def-state state)
(defmacro def-something [n] `(defn ~n [] :project))
(def-something project-made)"
    (fn [ns-sym source]
      (let [names '[->Foo map->Foo ->Bar Ping ping state project-made]
            vars (mapv #(ns-resolve ns-sym %) names)
            before (reload/interned-meta ns-sym)]
        (load-file (.getPath source))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (empty? (:unmapped r)))
          ;; Why they survive, stated so this test can fail: a top-level defrecord,
          ;; deftype, defprotocol or def-making macro is re-evaluated by the load, which
          ;; gives every var it makes a fresh metadata map. None of them is a candidate,
          ;; so the file's text (which names Foo and Bar, never ->Foo or map->Foo) is
          ;; never asked about them. The candidate cases are the test below.
          (doseq [sym names]
            (is (not (identical? (get before sym) (meta (ns-resolve ns-sym sym))))
                (str sym " was redefined by the load")))
          (is (empty? (:kept r)))
          (is (zero? (:dynamic r)))
          (doseq [[sym v] (map vector names vars)]
            (is (some? v) (str sym))
            (is (identical? v (ns-resolve ns-sym sym)) (str sym)))
          (is (= {:x 7} (into {} ((ns-resolve ns-sym '->Foo) 7))))
          (is (= {:x 8} (into {} ((ns-resolve ns-sym 'map->Foo) {:x 8}))))
          (is (some? ((ns-resolve ns-sym '->Bar) 9)))
          (is (= 1 @@(ns-resolve ns-sym 'state)))
          (is (= :project ((ns-resolve ns-sym 'project-made)))))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-macro-made-definition-the-reload-does-not-redefine-is-kept
  ;; The field case, through a macro: the def is inside a body that ran once (so the var
  ;; carries this file's :file) and the reload does not run it again, so nothing but the
  ;; file's own text can say the var is still defined.
  (with-source "(defmacro def-lazy [n] `(defn ~(symbol (str \"start-\" n)) [] (def ~n :lazy)))
(def-lazy lazily)
(defn init [] (defrecord Foo [x]) (deftype Bar [y]) (defprotocol Ping (ping [this])))"
    (fn [ns-sym source]
      ((ns-resolve ns-sym 'start-lazily))
      ((ns-resolve ns-sym 'init))
      (let [before (reload/interned-meta ns-sym)
            candidate? (fn [sym] (identical? (get before sym) (meta (ns-resolve ns-sym sym))))]
        (load-file (.getPath source))
        (let [r (reload/unmap-stale! ns-sym before source)
              kept (into {} (map (juxt :var :reason)) (:kept r))]
          (is (empty? (:unmapped r)))
          (is (candidate? 'lazily) "the macro's def ran once; the reload does not run it again")
          (is (= :still-in-file (kept 'lazily))
              "and the macro call in the file still names it")
          (is (= :lazy @(ns-resolve ns-sym 'lazily)))
          (is (candidate? 'Ping) "defprotocol inside a body the reload does not run")
          (is (= :still-in-file (kept 'Ping)) "the file still has the defprotocol form")
          (is (= 1 (:dynamic r)) "the protocol method var has no :file: kept, never unmapped")
          (is (some? (ns-resolve ns-sym 'ping)))
          (doseq [sym '[->Foo map->Foo ->Bar start-lazily]]
            (is (not (candidate? sym))
                (str sym " is redefined by the load, so the file's text is never consulted for it"))
            (is (some? (ns-resolve ns-sym sym)) (str sym)))
          (is (= {:x 7} (into {} ((ns-resolve ns-sym '->Foo) 7))))
          (is (some? ((ns-resolve ns-sym '->Bar) 9))))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest an-interned-var-survives-a-reload
  (with-source "(defn start! [] (intern *ns* 'runtime-value :connected))"
    (fn [ns-sym source]
      ((ns-resolve ns-sym 'start!))
      (let [v (ns-resolve ns-sym 'runtime-value)
            before (reload/interned-meta ns-sym)]
        (is (nil? (:file (meta v))))
        (load-file (.getPath source))
        (let [r (reload/unmap-stale! ns-sym before source)]
          (is (= 1 (:dynamic r)))
          (is (identical? v (ns-resolve ns-sym 'runtime-value)))
          (is (= :connected @v)))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest the-first-reload-after-start-unmaps-a-deleted-definition
  (with-source "(defn gone [] :old)"
    (fn [ns-sym source]
      (spit source "")
      ;; No prior reload/unmap call or previous-source cache exists.
      (let [before (reload/interned-meta ns-sym)]
        (load-file (.getPath source))
        (is (= '[gone] (mapv :var (:unmapped (reload/unmap-stale! ns-sym before source)))))
        (is (nil? (ns-resolve ns-sym 'gone)))))))

;; INTENT-TEST: LOOP-RELOAD-UNMAP-018
(deftest a-commented-out-test-is-not-run-or-counted-by-a-warm-check
  ;; The whole path a warm check takes, not the unmap function alone: a transient test
  ;; namespace under test/ (deleted in finally, so no suite run ever discovers it) is
  ;; checked twice through runner/check!, with a real nested Kaocha run each time. The
  ;; nested run gets its own report counters, as a probe's run does, so the enclosing
  ;; suite's counts are untouched.
  (let [f (io/file "test/kaocha_sublime/comment_probe_test.clj")
        ns-sym 'kaocha-sublime.comment-probe-test
        header (str "(ns " ns-sym " (:require [clojure.test :refer [deftest is]]))\n"
                    "(deftest anchor (is true))\n")
        check! (fn []
                 (let [answer (atom nil)]
                   (with-out-str
                     (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
                       (reset! answer (runner/check! [(str ns-sym)]))))
                   @answer))]
    (try
      (spit f (str header "(deftest commented-out (is true))\n"))
      (let [r (check!)]
        (is (= "pass" (:status r)) (:error r))
        (is (= 2 (get-in r [:counts :tests])) "both tests ran"))
      (spit f (str header "(comment (deftest commented-out (is true)))\n"))
      (let [r (check!)]
        (is (= "pass" (:status r)) (:error r))
        (is (= 1 (get-in r [:counts :tests]))
            "the commented-out test is neither executed nor counted, as in a cold JVM")
        (is (some #(= "commented-out" (:var %)) (:unmapped r))
            "and the receipt says the reload unmapped it")
        (is (nil? (ns-resolve ns-sym 'commented-out))))
      ;; Review round 5 (codex): a bare comment inside a top-level do is still the comment
      ;; form, so the test it wraps is gone in a cold JVM and must be gone warm too.
      (spit f (str header "(deftest commented-out (is true))\n"))
      (is (= 2 (get-in (check!) [:counts :tests])) "the test is back")
      (spit f (str header "(do (comment (deftest commented-out (is true))))\n"))
      (let [r (check!)]
        (is (= "pass" (:status r)) (:error r))
        (is (= 1 (get-in r [:counts :tests]))
            "a test commented out inside a top-level do is not counted either")
        (is (nil? (ns-resolve ns-sym 'commented-out))))
      (finally
        (.delete f)
        (remove-ns ns-sym)))))
