(ns kaocha-sublime.defects-test
  (:refer-clojure :exclude [run!])
  (:import (java.lang ProcessHandle))
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [kaocha.api :as api]
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
