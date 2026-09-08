(ns kaocha-sublime.defects-test
  (:refer-clojure :exclude [run!])
  (:import (java.lang ProcessHandle))
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [kaocha.api :as api]
            [kaocha.classpath :as classpath]
            [kaocha.testable :as testable]
            [kaocha.plugin.run-receipt :as receipt]
            [kaocha-sublime.agent-test :refer [records]]
            [kaocha-sublime.candidate :as candidate]
            [kaocha-sublime.image :as image]
            [kaocha-sublime.protocol :as protocol]
            [kaocha-sublime.status :as status]
            [kaocha-sublime.receipt-test :refer [temp-dir fixture-config]]))

(defn config [dir]
  (assoc (fixture-config dir) :kaocha/reporter ['kaocha.report/agent]))

(defn run! [cfg]
  (let [answer (atom nil)]
    (with-out-str
      (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
        (reset! answer (try (api/run cfg) (catch Throwable t t)))))
    @answer))

(defn emergency-cleanup! []
  ;; Only for the red baseline: keep a leaked lease from poisoning other tests.
  (when-let [s @image/active]
    (when-let [l (get-in s [:lease :lock])] (when (.isValid l) (.release l)))
    (when-let [c (get-in s [:lease :channel])] (.close c)))
  (when (.isHeldByCurrentThread image/run-lock) (.unlock image/run-lock))
  (reset! image/active nil))

;; INTENT-TEST: AGENT-LEASE-014
(deftest failed-load-releases-lease
  (doseq [failure [:empty :throw :interrupt]]
    (let [dir (temp-dir) cfg (config dir)]
      (try
        (let [bad (case failure
                    :empty (run! (assoc cfg :kaocha.filter/focus [:mistyped.namespace]))
                    :throw (with-redefs [testable/load-testables (fn [& _] (throw (ex-info "load failed" {})))]
                             (run! cfg))
                    :interrupt (with-redefs [testable/load-testables (fn [& _] (throw (InterruptedException. "load interrupted")))]
                                 (run! cfg)))
              streams (filter #(.endsWith (.getName %) ".events") (file-seq (io/file dir)))
              events (mapcat records streams)]
          (is (instance? Throwable bad))
          (is (some #(and (= "STATUS" (first %)) (= "refused" (get-in % [1 :state]))) events))
          (is (not (.isLocked image/run-lock)))
          (is (map? (run! cfg)) "The next probe in the same image must run"))
        (finally (emergency-cleanup!))))))

;; INTENT-TEST: AGENT-LEASE-014
(deftest dead-owner-reservation-is-reclaimed
  (let [dir (temp-dir) tmp (io/file dir "dead.edn.tmp")
        dead {:run-id "dead" :started "2000-01-01T00:00:00Z"
              :jvm-pid (.pid (ProcessHandle/current)) :process-start "2000-01-01T00:00:00Z"}]
    ;; Recycled PID: existence alone is not a live-owner proof.
    (candidate/atomic-edn! tmp dead)
    (try
      (let [r (run! (config dir))
            path (:kaocha.plugin.run-receipt/path r)
            events (when path (records (:events (candidate/read-edn path))))]
        (is (map? r))
        (is (not (.exists tmp)))
        (is (= "dead" (:run-id (candidate/read-edn (io/file dir "INTERRUPTED")))))
        (is (some #(and (= "STATUS" (first %)) (= "killed" (get-in % [1 :state]))
                       (= "process-dead" (get-in % [1 :reason])) (= "dead" (get-in % [1 :id]))) events)))
      (finally (emergency-cleanup!)))))

;; INTENT-TEST: AGENT-LEASE-014
(deftest reservation-and-publication-failures-release-lease
  (doseq [v [#'candidate/publish-session! #'receipt/publish-latest!]]
    (let [dir (temp-dir) cfg (config dir)]
      (try
        (is (instance? Throwable
                       (with-redefs-fn {v (fn [& _] (throw (ex-info "publication failed" {})))}
                         #(run! cfg))))
        (is (not (.isLocked image/run-lock)))
        (is (not-any? #(= "RUN-DONE" (first %))
                      (mapcat records (filter #(.endsWith (.getName %) ".events") (file-seq (io/file dir))))))
        (is (map? @(future (run! cfg))) "A different nREPL thread can acquire the next lease")
        (finally (emergency-cleanup!))))))

;; INTENT-TEST: AGENT-LEASE-014
(deftest failure-before-stream-does-not-leave-an-empty-reservation
  (let [dir (temp-dir) cfg (config dir)]
    (is (instance? Throwable
                   (with-redefs [image/capture (fn [_] (throw (ex-info "snapshot failed" {})))]
                     (run! cfg))))
    (is (empty? (status/running-files dir)))
    (is (not (.isLocked image/run-lock)))
    (is (map? (run! cfg)))))

;; INTENT-TEST: AGENT-LEASE-014
(deftest lease-covers-the-final-summary
  (let [held (atom nil) original clojure.test/do-report]
    (with-redefs [clojure.test/do-report
                  (fn [event]
                    (when (= :summary (:type event))
                      (reset! held (.isHeldByCurrentThread image/run-lock)))
                    (original event))]
      (run! (config (temp-dir))))
    (is (true? @held))
    (is (not (.isLocked image/run-lock)))))

(defn skip-fixture [dir]
  (let [root (str dir "/test") file (str root "/declared_gate_test.clj")]
    (io/make-parents file)
    (spit file "(ns declared-gate-test (:require [clojure.test :refer [deftest is]]))\n(deftest green (is true))\n(deftest another-green (is true))\n(deftest ^:ci configured-skip (throw (Exception. \"must be skipped\")))\n")
    (-> (config (str dir "/runs"))
        (assoc :kaocha/tests [{:kaocha.testable/id :unit :kaocha.testable/type :kaocha.type/clojure.test
                              :kaocha/source-paths [] :kaocha/test-paths [root]
                              :kaocha/ns-patterns [".*"] :kaocha.filter/skip-meta [:e2e :slow :pg :ci]}]))))

;; INTENT-TEST: AGENT-DECLARED-GATE-015
(deftest declared-skips-complete-the-gate
  (let [dir (temp-dir) cfg (skip-fixture dir)
        result (run! cfg) path (:kaocha.plugin.run-receipt/path result)
        r (candidate/read-edn path) done (second (last (records (:events r))))]
    (is (= :complete (:coverage r)))
    (is (= {:selected 1 :total 1} (:closure r)))
    (is (= 2 (get-in r [:totals :tests])))
    (is (= 1 (get-in r [:totals :skipped])))
    (is (= "pass" (:outcome done)))
    (is (= [{:id :declared-gate-test/configured-skip :skip-meta [:ci]}] (:declared-skips r)))
    (is (= [{:id :unit :skip-meta [:e2e :slow :pg :ci]}] (:skip-rules r)))
    ;; Identity is separately proved by a cold/watch run; isolate coverage here.
    (let [token (candidate/mark! (str dir "/runs") [] {:kind :full})
          r (assoc r :identity :exact)]
      (kaocha-sublime.agent-test/publish-token! (str dir "/runs") r)
      (is (= 0 (:exit (status/observe {:dir (str dir "/runs") :token token})))))))

;; INTENT-TEST: AGENT-DECLARED-GATE-015
(deftest focus-still-leaves-missing-coverage
  (let [cfg (skip-fixture (temp-dir))
        result (run! (assoc cfg :kaocha.filter/focus [:declared-gate-test/green]))
        r (candidate/read-edn (:kaocha.plugin.run-receipt/path result))]
    (is (= :partial (:coverage r)))
    (is (= :incomplete (protocol/outcome (:totals r) (:coverage r))))
    (is (= 2 (get-in r [:totals :skipped])))))

;; INTENT-TEST: AGENT-REFUSAL-ID-016
(deftest heap-refusal-honors-launcher-id
  (let [dir (temp-dir) id "launcher-owned-heap-refusal"
        code (str "(require '[kaocha.api :as api] '[kaocha-sublime.receipt-test :as fixture])"
                  "(try (api/run (assoc (fixture/fixture-config " (pr-str dir) ")"
                  " :kaocha.plugin.run-receipt/agent? true :kaocha.plugin.run-receipt/heap-ceiling-bytes 1))"
                  " (catch clojure.lang.ExceptionInfo e (System/exit (or (:kaocha/early-exit (ex-data e)) 99))))")
        result (shell/sh "java" "-Xms64m" "-Xmx512m" "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" code :env (assoc (into {} (System/getenv)) "KAOCHA_RUN_ID" id))
        path (io/file dir (str id ".events"))]
    (is (= 2 (:exit result)))
    (is (.exists path))
    (when (.exists path)
      (let [events (records path)]
        (is (= ["STATUS"] (mapv first events)))
        (is (= id (get-in events [0 1 :id])))
        (is (= "heap-cap" (get-in events [0 1 :reason])))))
    (is (not (.exists (io/file dir "LATEST"))))))

(defn probe-run [targets cfg]
  (if-let [f (try (requiring-resolve 'kaocha-sublime.probe/run) (catch java.io.FileNotFoundException _ nil))]
    (let [answer (atom nil)]
      (with-out-str
        (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
          (reset! answer (f targets cfg))))
      @answer)
    ;; Execute item 4's old adapter path for the red half of the regression.
    (do (doseq [n targets] (require n :reload))
        (let [r (run! (assoc cfg :kaocha.filter/focus (mapv keyword targets)))]
          {:path (:kaocha.plugin.run-receipt/path r)}))))

;; INTENT-TEST: AGENT-PROBE-RELOAD-017
(deftest deleted-test-vars-do-not-survive-probes
  (let [dir (temp-dir) root (str dir "/test") file (str root "/probe_deleted_test.clj")
        cfg (assoc (config (str dir "/runs")) :kaocha/tests
                   [{:kaocha.testable/id :unit :kaocha.testable/type :kaocha.type/clojure.test
                     :kaocha/source-paths [] :kaocha/test-paths [root] :kaocha/ns-patterns ["-test$"]}])
        header "(ns probe-deleted-test (:require [clojure.test :refer [deftest is]]))\n"]
    (io/make-parents file)
    (classpath/add-classpath root)
    (try
      (spit file (str header "(deftest stays (is true))\n(deftest deleted-red (is false))\n"))
      (let [r (candidate/read-edn (:path (probe-run '[probe-deleted-test] cfg)))]
        (is (= 1 (get-in r [:totals :fail]))))
      (spit file (str header "(deftest stays (is true))\n"))
      (let [r (candidate/read-edn (:path (probe-run '[probe-deleted-test] cfg)))]
        (is (= 1 (get-in r [:totals :tests])))
        (is (= 0 (get-in r [:totals :fail])))
        (is (nil? (ns-resolve 'probe-deleted-test 'deleted-red))))
      ;; Deleted green Vars also must not manufacture executed coverage.
      (spit file header)
      (probe-run '[probe-deleted-test] cfg)
      (is (nil? (ns-resolve 'probe-deleted-test 'stays)))
      (is (not (.isLocked image/run-lock)))
      (finally (emergency-cleanup!)))))

;; INTENT-TEST: AGENT-PROBE-RELOAD-017
(deftest mistyped-probe-is-typed-and-the-next-probe-runs
  (let [cfg (skip-fixture (temp-dir))
        bad (probe-run '[mistyped.missing-test] cfg)
        events (records (:events bad))]
    (is (= 2 (:exit bad)))
    (is (= ["RUN-START" "FAIL" "STATUS"] (mapv first events)))
    (is (= "load" (get-in events [1 1 :kind])))
    (is (= "refused" (get-in events [2 1 :state])))
    (is (not (.isLocked image/run-lock)))
    (let [good @(future (probe-run '[declared-gate-test] cfg))]
      (is (= 0 (:exit good)))
      (is (= "pass" (get-in (last (records (:events good))) [1 :outcome]))))))

;; INTENT-TEST: AGENT-ARITHMETIC-018
(deftest inconsistent-arithmetic-cannot-be-published
  (let [dir (temp-dir) cfg (skip-fixture dir) original @#'receipt/totals
        result (with-redefs-fn {#'receipt/totals (fn [r] (update (original r) :assertions inc))}
                 #(run! cfg))
        files (file-seq (io/file dir "runs"))
        events (mapcat records (filter #(.endsWith (.getName %) ".events") files))]
    (is (instance? Throwable result))
    (is (not (.exists (io/file dir "runs/LATEST"))))
    (is (not-any? #(.endsWith (.getName %) ".edn") files))
    (is (= ["RUN-START" "STATUS"] (mapv first events)))
    (is (= "protocol-error" (get-in (last events) [1 :reason])))
    (is (not (.isLocked image/run-lock)))))

;; INTENT-TEST: AGENT-ARITHMETIC-018
(deftest inconsistent-authenticated-receipt-is-never-reader-pass
  (let [{:keys [dir record] :as opts} (kaocha-sublime.agent-test/token-fixture)
        r (assoc-in record [:totals :assertions] 999)
        path (kaocha-sublime.agent-test/publish-token! dir r)
        done (protocol/done-record r path (candidate/file-hash path))
        response (status/observe opts)]
    (is (not (protocol/valid-record? :done done)))
    (is (thrown? clojure.lang.ExceptionInfo (protocol/record-line :done done)))
    (is (= 2 (:exit response)))
    (is (= :protocol-error (get-in response [:record :reason])))))
