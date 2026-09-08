(ns kaocha-sublime.receipt-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kaocha.api :as api] [kaocha.config :as config]
            [kaocha.repl :as repl] [kaocha.result :as result]
            [kaocha.plugin.run-receipt :as receipt]
            [kaocha-sublime.status :as status]
            [kaocha.watch :as watch]
            [lambdaisland.tools.namespace.dir :as ns-dir])
  (:import (java.nio.file Files)))

(defn temp-dir [] (str (Files/createTempDirectory "kaocha-sublime-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn fixture-config [dir]
  (config/merge-config (config/load-config nil)
    {:kaocha/tests [{:kaocha.testable/id :unit
                     :kaocha.testable/type :kaocha.type/clojure.test
                              :kaocha/ns-patterns ["-test$"]
                     :kaocha/source-paths ["fixtures/tiny/src"]
                     :kaocha/test-paths ["fixtures/tiny/test"]}]
     :kaocha/plugins [:kaocha.plugin/filter :kaocha.plugin/capture-output :kaocha.plugin/run-receipt]
     :kaocha/reporter ['kaocha.report/documentation]
     :kaocha/color? false :kaocha.plugin.run-receipt/dir dir
     :kaocha.plugin.run-receipt/include-tests? true}))

;; INTENT-TEST: STATUS-POINTER-001
(deftest latest-points-to-completed-receipt
  (let [dir (temp-dir)]
    (with-out-str
      (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
        (doseq [_ (range 2)]
          (let [r (api/run (fixture-config dir))
                path (:kaocha.plugin.run-receipt/path r)
                receipt (edn/read-string (slurp path))
                latest (io/file dir "LATEST")]
            (is (.exists latest))
            (when (.exists latest)
              (is (= {:path path :run-id (:run-id receipt) :finished (:finished receipt)}
                     (edn/read-string (slurp latest)))))
            (is (= [] (get-in receipt [:trigger :files])))
            (is (string? (get-in receipt [:trigger :at])))
            (is (false? (:auto-followup? receipt)))))))))

;; INTENT-TEST: STATUS-TRIGGER-003
(deftest watcher-events-and-automatic-rescan
  ;; Install through the ordinary plugin configuration entrance.
  (receipt/run-receipt-config-hook {})
  (let [dir (temp-dir) a (io/file dir "a.clj") b (io/file dir "b.clj")
        q (watch/make-queue)]
    (spit a "(ns a)") (spit b "(ns b)")
    ;; Beholder reports java.nio.file.Path; Hawk reports java.io.File.
    (watch/qput q (.toPath a)) (watch/qput q b)
    (let [[tracker _] (watch/wait-and-rescan! q {} [(io/file dir)] [])]
      (is (= [(str a) (str b)] (get-in tracker [:kaocha.plugin.run-receipt/trigger :files])))
      (is (false? (:kaocha.plugin.run-receipt/auto-followup? tracker)))
      (is (string? (get-in tracker [:kaocha.plugin.run-receipt/trigger :at])))
      (let [auto (watch/drain-and-rescan! q tracker [(io/file dir)])]
        (is (true? (:kaocha.plugin.run-receipt/auto-followup? auto)))
        (is (= [] (get-in auto [:kaocha.plugin.run-receipt/trigger :files]))))
      (watch/qput q :enter)
      (let [[manual _] (watch/wait-and-rescan! q tracker [(io/file dir)] [])]
        (is (false? (:kaocha.plugin.run-receipt/auto-followup? manual)))
        (is (= [] (get-in manual [:kaocha.plugin.run-receipt/trigger :files])))))))

(deftest running-reservation-precedes-watcher-scan
  (receipt/run-receipt-config-hook {})
  (let [dir (temp-dir) q (watch/make-queue) file (io/file dir "a.clj")]
    (spit file "(ns a)")
    (watch/qput q (.toPath file))
    (with-redefs [ns-dir/scan-dirs
                  (fn [tracker _]
                    (let [running (filter #(str/ends-with? (.getName %) ".edn.tmp")
                                          (.listFiles (io/file dir)))]
                      (is (= 1 (count running)) "RUNNING covers rescan, not just test execution")
                      (when (seq running)
                        (is (= :running (:status (edn/read-string (slurp (first running))))))))
                    tracker)]
      (watch/wait-and-rescan! q {:kaocha.plugin.run-receipt/settings
                                 {:kaocha.plugin.run-receipt/dir dir}}
                             [(io/file dir)] []))))

(deftest interrupted-publication-does-not-expose-old-verdict
  (let [dir (temp-dir) cfg (fixture-config dir)]
    (with-out-str
      (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
        (api/run cfg)
        (let [before (slurp (io/file dir "LATEST"))]
          (with-redefs-fn {#'receipt/publish-latest!
                          (fn [_ _] (throw (ex-info "publication interrupted" {})))}
            #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"publication interrupted" (api/run cfg))))
          (is (= before (slurp (io/file dir "LATEST"))))
          (is (= 2 (count (filter #(str/ends-with? (.getName %) ".edn")
                                 (.listFiles (io/file dir))))))
          (is (str/starts-with? (status/status-line {:dir dir}) "KAOCHA-STATUS RUNNING ")))))))

(deftest complete-receipt-matches-reporter-and-capture
  (let [dir (temp-dir) r (atom nil)
        output (with-out-str (binding [clojure.test/*test-out* *out*
                                      clojure.test/*report-counters* nil]
                               (reset! r (api/run (fixture-config dir)))))
        path (:kaocha.plugin.run-receipt/path @r)
        receipt (edn/read-string (slurp path))
        outcomes (into {} (map (juxt :id identity) (:tests receipt)))]
    (is (= {:tests 5 :assertions 4 :pass 2 :fail 1 :error 1 :pending 1} (:totals receipt)))
    (is (str/includes? output "5 tests, 4 assertions"))
    (is (= (str "RUN-RECEIPT " path) (last (str/split-lines output))))
    (is (= 2 (count (:failures receipt))))
    (is (every? #(and (:file %) (:line %)) (:failures receipt)))
    (is (str/includes? (:stdout (outcomes :sample.core-test/passing)) "owned-by-passing"))
    (is (not (str/includes? (:stdout (outcomes :sample.core-test/passing)) "owned-by-failing")))
    (is (= :pending (:outcome (outcomes :sample.core-test/pending))))
    (is (not (.exists (io/file (str path ".tmp")))))
    (is (pos? (:wall-ms receipt)))
    (is (= 64 (count (:config-hash receipt))))
    (is (every? #(str/ends-with? (% receipt) "Z") [:started :finished]))))

(deftest warm-repl-keeps-path-for-each-run
  (let [dir (temp-dir) cfg (fixture-config dir)
        run-one #(let [r (atom nil)]
                   (with-out-str (binding [clojure.test/*report-counters* nil
                                          clojure.test/*test-out* *out*]
                                   (reset! r (repl/run 'sample.core-test cfg)))) @r)
        a (run-one) b (run-one)]
    (is (= 4 (:kaocha.result/count a)))
    (is (string? (:kaocha.plugin.run-receipt/path a)))
    (is (not= (:kaocha.plugin.run-receipt/path a) (:kaocha.plugin.run-receipt/path b)))
    (is (= 2 (count (filter #(str/ends-with? (.getName %) ".edn") (file-seq (io/file dir))))))
    (is (= (dissoc a :kaocha.plugin.run-receipt/path) (dissoc b :kaocha.plugin.run-receipt/path)))))

(deftest adapter-leaves-unrelated-totals-alone
  (is (= {:kaocha.result/count 0 :kaocha.result/pass 0 :kaocha.result/fail 0
          :kaocha.result/error 0 :kaocha.result/pending 0}
         (result/totals []))))

(deftest extended-failure-events-and-arbitrary-messages-remain-edn
  (let [dir (temp-dir)
        cfg (-> (fixture-config dir)
                (assoc-in [:kaocha/tests 0 :kaocha/test-paths] ["fixtures/edge/test"]))
        r (atom nil)]
    (with-out-str (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
                    (reset! r (api/run cfg))))
    (let [receipt (edn/read-string (slurp (:kaocha.plugin.run-receipt/path @r)))]
      (is (= 2 (get-in receipt [:totals :fail])))
      (is (= 2 (count (:failures receipt))))
      (is (every? #(or (nil? (:message %)) (string? (:message %))) (:failures receipt))))))

(deftest repl-printer-limits-cannot-truncate-receipts
  (let [dir (temp-dir) r (atom nil)]
    (with-out-str
      (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil
                *print-length* 1 *print-level* 1 *print-readably* false]
        (reset! r (api/run (fixture-config dir)))))
    (let [receipt (edn/read-string (slurp (:kaocha.plugin.run-receipt/path @r)))]
      (is (= 5 (get-in receipt [:totals :tests])))
      (is (= 2 (count (:failures receipt))))
      (is (string? (:config-hash receipt))))))
