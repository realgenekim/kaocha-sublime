(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])
(def root "/var/tmp/forge/kaocha-sublime-fx")
(def report (atom []))
(defn record! [x] (swap! report conj x) (prn x))
(doseq [[spec log-names] [["mvr" ["mvr-final"]] ["ccfp" ["ccfp" "ccfp-final"]]]]
  (let [dir (io/file "evidence/receipts" spec)
        files (filter #(str/ends-with? (.getName %) ".edn") (file-seq dir))
        receipts (into {} (map (fn [f] [(str (io/file root spec "target/kaocha-runs" (.getName f)))
                                        (edn/read-string (slurp f))])) files)
        ids (map :run-id (vals receipts))]
    (assert (= (count ids) (count (set ids))))
    (assert (not-any? #(str/ends-with? (.getName %) ".tmp") (file-seq dir)))
    (doseq [[path r] receipts]
      (assert (every? #(contains? r %) [:run-id :started :finished :wall-ms :totals :failures
                                       :config-hash :git-sha :jvm-pid :kaocha-version :plugin-version]))
      (assert (= "1.91.1392" (:kaocha-version r)))
      (assert (not (neg? (:wall-ms r))))
      (assert (<= 0 (.compareTo (java.time.Instant/parse (:finished r))
                              (java.time.Instant/parse (:started r)))))
      (let [{:keys [assertions pass fail error]} (:totals r)]
        (assert (= assertions (+ pass fail error)))
        (assert (= (+ fail error) (count (:failures r))) path)))
    (doseq [log-name log-names]
      (let [lines (str/split-lines (slurp (io/file "evidence/watch" (str log-name "-watch.log"))))
            clean #(str/replace % #"\u001b\[[0-9;]*m" "")
            summaries (keep #(re-find #"(\d+) tests,\s+(\d+) assertions.*" (clean %)) lines)
            markers (keep #(second (re-find #"\tRUN-RECEIPT (.*)$" %)) lines)]
        (assert (= (count summaries) (count markers)))
        (doseq [[[_ tests assertions] path] (map vector summaries markers)]
          (assert (= (Long/parseLong tests) (get-in receipts [path :totals :tests])) path)
          (assert (= (Long/parseLong assertions) (get-in receipts [path :totals :assertions])) path))
        (record! {:specimen spec :log log-name :runs (count markers)
                  :one-receipt-per-summary true :reporter-totals-equal true})))
    (record! {:specimen spec :complete-receipts (count receipts) :partial-receipts 0})))
(doseq [kind ["source" "identity"] phase ["change" "restore"]]
  (let [log (slurp (io/file "evidence" (str "ccfp-" kind "-" phase ".log")))
        reload (set (str/split (second (re-find #"\[watch\] Reloading #\{([^}]+)\}" log)) #"\s+"))
        path (second (re-find #"\tRUN-RECEIPT (.*)" log))
        r (edn/read-string (slurp (io/file "evidence/receipts/ccfp" (.getName (io/file path)))))
        closure (set (map str (get-in r [:affected :closure])))
        selected (set (map str (get-in r [:affected :namespaces])))]
    (assert (= reload closure))
    (assert (= selected closure))
    (assert (not (contains? selected "cfp-scheduler-killer.blob-durability-test")))
    (record! {:witness kind :phase phase :closure (count closure)
              :tracker-reload-equal true :unrelated-excluded true :totals (:totals r)})))
(spit "evidence/verification.edn" (str (pr-str @report) "\n"))
(shutdown-agents)
