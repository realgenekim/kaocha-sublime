(ns kaocha-sublime.agent-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaocha-sublime.receipt-test :refer [temp-dir]]))

(defn invoke [v & args]
  (apply (or (try (requiring-resolve v) (catch Exception _ nil))
             (fn [& _] (throw (ex-info (str "Not implemented: " v) {})))) args))

;; INTENT-TEST: AGENT-CANDIDATE-006
(deftest mark-binds-content
  (let [root (temp-dir) dir (str root "/runs") file (str root "/src/a.clj")]
    (io/make-parents file) (spit file "(ns a)")
    (invoke 'kaocha-sublime.candidate/publish-session! dir
            {:workspace root :session "session-a" :generation "config-a"
             :roots [(str root "/src")] :config-files []})
    (let [a (invoke 'kaocha-sublime.candidate/mark! dir [file] {:kind :full})
          b (invoke 'kaocha-sublime.candidate/mark! dir [file] {:kind :full})]
      (is (= a b) "Identical candidates coalesce")
      (let [record (edn/read-string (slurp (str dir "/tokens/" a ".edn")))]
        (is (= root (:workspace record)))
        (is (= "session-a" (:session record)))
        (is (= {:kind :full} (:required-scope record)))
        (is (= 64 (count (get-in record [:required-paths file])))))
      (spit file "(ns b)")
      (is (not= a (invoke 'kaocha-sublime.candidate/mark! dir [file] {:kind :full})))
      (io/delete-file file)
      (let [deleted (invoke 'kaocha-sublime.candidate/mark! dir [file] {:kind :full})
            r (edn/read-string (slurp (str dir "/tokens/" deleted ".edn")))]
        (is (contains? (:required-paths r) file))
        (is (nil? (get-in r [:required-paths file])))))))

;; INTENT-TEST: AGENT-ACCEPT-007
(deftest follow-acceptance-state-space
  (doseq [identity [:exact :unknown]
          coverage [:complete :partial :unknown]
          outcome [:pass :fail :error :incomplete]]
    (let [expected (cond (not= identity :exact) 2
                         (contains? #{:fail :error} outcome) 1
                         (and (= coverage :complete) (= outcome :pass)) 0
                         :else 2)]
      (is (= expected (invoke 'kaocha-sublime.protocol/exit-code
                              {:identity identity :coverage coverage :outcome outcome}))
          (str [identity coverage outcome])))))

;; INTENT-TEST: AGENT-DEATH-008
(deftest dead-candidate-never-done
  (is (= :dead (invoke 'kaocha-sublime.candidate/process-state
                       {:jvm-pid 999999999 :process-start "2000-01-01T00:00:00Z"})))
  (is (= :unknown (invoke 'kaocha-sublime.candidate/process-state {}))))

(deftype Explosive [])
(defmethod print-method Explosive [_ _] (throw (Exception. "printer must never run")))

;; INTENT-TEST: AGENT-ENCODER-009
(deftest bounded-untrusted-values
  (let [forced (atom 0)
        lazy-value (map (fn [x] (swap! forced inc) x) (range))]
    (doseq [value [lazy-value (Explosive.) {:huge lazy-value}
                   (apply str (repeat 10000 "\n\u001b\u202e"))]]
      (let [p (invoke 'kaocha-sublime.protocol/preview value 256)]
        (is (<= (count (invoke 'kaocha-sublime.protocol/json (:text p))) 256))
        (is (or (:truncated? p) (:unavailable? p)))))
    (is (zero? @forced)))
  (is (= "\"a\\n\\u001b\\u202e\\u007f\""
         (invoke 'kaocha-sublime.protocol/json "a\n\u001b\u202e\u007f"))))

;; INTENT-TEST: AGENT-PUBLISH-010
(deftest agent-publication-order
  (let [r (shell/sh "bin/kaocha-status" "--follow" "--token" "absent" "--timeout" "0")]
    (is (= 2 (:exit r)))
    (is (str/starts-with? (:out r) "STATUS {"))
    (is (= 1 (count (str/split-lines (:out r)))))))

;; INTENT-TEST: AGENT-HEAP-011
(deftest heap-admission-state-space
  (doseq [[args effective admitted?]
          [[[] 536870912 false]
           [["-Xms64m" "-Xmx512m"] 536870912 true]
           [["-Xmx512m"] 1073741824 false]
           [["-Xmx1g"] 1073741824 false]]]
    (is (= admitted? (:admitted? (invoke 'kaocha-sublime.heap/admission args effective 536870912))))))

;; INTENT-TEST: AGENT-IMAGE-012
(deftest observed-generation-invalidates-identity
  (doseq [[before after same-files? expected]
          [[4 4 true true] [4 5 true false] [4 4 false false] [4 5 false false]]]
    (is (= expected (invoke 'kaocha-sublime.candidate/stable-image?
                            {:observed before :manifest {"a" "one"}}
                            {:observed after :manifest {"a" (if same-files? "one" "two")}})))))

;; INTENT-TEST: AGENT-BUDGET-013
(deftest failure-budget-accounting
  (let [dir (temp-dir)
        state (invoke 'kaocha-sublime.agent/new-stream!
                      {:run-id "budget" :mode :gate :candidate nil :dir dir})]
    (dotimes [_ 25]
      (invoke 'kaocha-sublime.agent/failure! state
              {:type :fail :expected '(= 1 2) :actual '(not (= 1 2))}))
    (let [{:keys [shown omitted bytes]} @(:budget state)
          lines (str/split-lines (slurp (:events state)))]
      (is (= 25 (+ shown omitted)))
      (is (<= shown 20))
      (is (<= bytes 16384))
      (is (= shown (count lines)))
      (is (every? #(<= (inc (count %)) 2048) lines)))))
