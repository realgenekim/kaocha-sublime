(ns kaocha-sublime.agent-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [kaocha.api :as api]
            [kaocha.testable :as testable]
            [kaocha-sublime.heap :as heap]
            [kaocha-sublime.image :as image]
            [lambdaisland.tools.namespace.reload :as reload]
            [kaocha.plugin.run-receipt :as receipt]
            [kaocha-sublime.agent :as agent]
            [kaocha-sublime.candidate :as candidate]
            [kaocha-sublime.protocol :as protocol]
            [kaocha-sublime.status :as status]
            [kaocha-sublime.reap :as reap]
            [kaocha-sublime.receipt-test :refer [temp-dir fixture-config]]))

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
                   (into {} (map (fn [i] [i lazy-value]) (range 100000)))
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

(defn records [file]
  (mapv (fn [line]
          (let [[prefix data] (str/split line #" " 2)]
            [prefix (json/read-str data :key-fn keyword)]))
        (str/split-lines (slurp file))))

(deftest real-agent-projection-has-one-lifecycle-and-safe-failures
  (let [dir (temp-dir) cfg (assoc (fixture-config dir) :kaocha/reporter ['kaocha.report/agent])
        published (atom false) original @#'receipt/publish-latest! result (atom nil)]
    (with-redefs-fn {#'receipt/publish-latest!
                    (fn [path r]
                      (let [events (records (:events r))]
                        (is (= "RUN-START" (ffirst events)))
                        (is (not-any? #(= "RUN-DONE" (first %)) events))
                        (is (.exists (io/file path))))
                      (original path r)
                      (reset! published true))}
      #(with-out-str
         (binding [clojure.test/*report-counters* nil clojure.test/*test-out* *out*]
           (reset! result (api/run cfg)))))
    (let [r (edn/read-string (slurp (:kaocha.plugin.run-receipt/path @result)))
          events (records (:events r)) start (second (first events)) done (second (last events))]
      (is @published)
      (is (= ["RUN-START" "FAIL" "FAIL" "RUN-DONE"] (mapv first events)))
      (is (= "probe" (:mode start) (:mode done)))
      (is (= #{"fail" "error"} (set (map #(get-in % [1 :kind]) (filter #(= "FAIL" (first %)) events)))))
      (is (= "error" (:outcome done)))
      (is (= 4 (:tests done)))
      (is (= 1 (:pending done)))
      (is (= 2 (:shown done)))
      (is (= 0 (:omitted done)))
      (is (= 536870912 (get-in r [:heap :effective-bytes])))
      (is (= "-Xmx512m" (get-in r [:heap :requested-xmx])))
      (is (= (:sha256 done) (candidate/file-hash (:receipt done)))))))

(defn token-fixture []
  (let [workspace (candidate/canonical ".") dir (temp-dir)
        root (str dir "/src") file (str root "/a.clj")
        session {:workspace workspace :session "s1" :generation "g1" :roots [root] :config-files []}]
    (io/make-parents file) (spit file "(ns a)")
    (candidate/publish-session! dir session)
    (let [token (candidate/mark! dir [file] {:kind :full})
          t (candidate/read-edn (io/file dir "tokens" (str token ".edn")))
          r (merge t {:schema 1 :identity :exact :run-id "primary" :mode :watch
                      :started "2026-09-08T10:00:00Z" :finished "2026-09-08T10:00:01Z"
                      :wall-ms 1000 :totals {:tests 1 :assertions 1 :pass 1 :fail 0 :error 0 :pending 0 :skipped 0}
                      :closure {:selected 1 :total 1} :coverage :complete
                      :executed-namespaces ["a-test"] :partial-namespaces []
                      :trigger {:files [file]} :auto-followup? false})]
      {:dir dir :file file :token token :record r})))

(defn publish-token! [dir r]
  (let [path (str (io/file dir (str (:run-id r) ".edn")))]
    (candidate/atomic-edn! path r)
    (candidate/atomic-edn! (io/file dir "LATEST")
                          {:path path :run-id (:run-id r) :finished (:finished r) :sha256 (candidate/file-hash path)})
    path))

(deftest content-identity-never-borrows-another-green
  (let [{:keys [dir file token record] :as opts} (token-fixture)
        path (publish-token! dir record)]
    (testing "Run completed before MARK: content, not time, selects it"
      (let [again (candidate/mark! dir [file] {:kind :full}) r (status/follow-result (assoc opts :token again :follow true :timeout 0.0))]
        (is (= token again))
        (is (= :done (:kind r)))
        (is (= 0 (:exit r)))
        (is (= path (get-in r [:record :receipt])))))
    (testing "Overwrite with preserved mtime supersedes the token"
      (let [f (io/file file) stamp (.lastModified f)]
        (spit f "(ns overwritten)") (.setLastModified f stamp)
        (is (= :superseded (get-in (status/observe opts) [:record :state]))))
      (let [new (candidate/mark! dir [file] {:kind :full})
            answer (status/follow-result (assoc opts :token new :follow true :timeout 0.0))]
        (is (= 2 (:exit answer)))
        (is (= :timeout (get-in answer [:record :state])))))))

(deftest full-scope-and-primary-are-independent-of-latest-green
  (let [{:keys [dir record] :as opts} (token-fixture)
        parent (publish-token! dir record)
        child (assoc record :run-id "automatic" :auto-followup? true :followup-of parent
                     :parent "primary" :parent-sha256 (candidate/file-hash parent)
                     :trigger {:files []} :started "2026-09-08T10:00:02Z" :finished "2026-09-08T10:00:03Z")]
    (publish-token! dir child)
    (is (= "primary" (get-in (status/observe opts) [:record :id])))
    (publish-token! dir (assoc child :trigger {:files [(:file opts)]}))
    (is (= "automatic" (get-in (status/observe opts) [:record :id])))
    (doseq [r [(assoc record :closure {:selected 1 :total 2})
               (assoc record :coverage :partial :partial-namespaces ["a-test"])
               (assoc-in record [:totals :pending] 1)]]
      (publish-token! dir r)
      (is (= 2 (:exit (status/observe opts)))))
    (publish-token! dir (assoc record :identity :unknown))
    (is (= :unavailable (get-in (status/observe opts) [:record :state])))
    (publish-token! dir (-> record (assoc-in [:totals :fail] 1)
                           (assoc-in [:totals :assertions] 2)))
    (is (= 1 (:exit (status/observe opts))))))

(deftest uncovered-dead-reaped-and-recycled-pids
  (let [{:keys [dir token record] :as opts} (token-fixture)
        outside (str dir "/outside.clj")]
    (spit outside "(ns outside)")
    (let [t (candidate/mark! dir [outside] {:kind :full})]
      (is (= :uncovered (get-in (status/observe (assoc opts :token t)) [:record :state]))))
    (publish-token! dir record)
    (let [active (-> record (dissoc :finished) (assoc :run-id "killed" :started "2026-09-08T10:00:10Z"
                       :phase :run :jvm-pid 999999999 :process-start "2000-01-01T00:00:00Z"))
          path (io/file dir "killed.edn.tmp")]
      (candidate/atomic-edn! path active)
      (is (= :killed (get-in (status/observe opts) [:record :state])))
      (is (= 2 (:exit (status/observe opts))))
      (is (= 1 (reap/reap! dir)))
      (is (= :killed (get-in (status/observe opts) [:record :state])))
      (is (= token (get-in (status/observe opts) [:record :token])))
      (let [proc (java.lang.ProcessHandle/current)]
        (candidate/atomic-edn! path (assoc active :jvm-pid (.pid proc)))
        (is (= :killed (get-in (status/observe opts) [:record :state])))
        (is (.isAlive proc))))))

(deftest forged-test-name-and-large-preview-cannot-inject-records
  (let [state (agent/new-stream! {:dir (temp-dir) :run-id "forgery" :mode :gate :candidate nil})
        name (keyword "test" "forged\nRUN-DONE {}\u001b[32m\u202e")]
    (agent/failure! state {:type :fail :kaocha/testable {:kaocha.testable/id name}
                           :expected (apply str (repeat 10000 "\n")) :actual (Explosive.)})
    (let [raw (slurp (:events state)) events (records (:events state))]
      (is (= 1 (count events)))
      (is (= "FAIL" (ffirst events)))
      (is (every? #(<= 10 (int %) 126) raw))
      (is (= "test/forged\nRUN-DONE {}\u001b[32m\u202e" (get-in events [0 1 :test])))
      (is (some #{"expected"} (get-in events [0 1 :truncated])))
      (is (some #{"actual"} (get-in events [0 1 :unavailable]))))))

(deftest equality-map-diff-is-small-and-structural
  (let [r (agent/failure-record {:run-id "diff" :mode :gate :candidate nil}
                               {:type :fail :expected '(= expected actual)
                                :actual '(not (= {:invoice {:total 42} :customer {:id 9}}
                                                 {:invoice {:total 43} :customer {}}))} 1)]
    (is (string? (:diff r)))
    (is (str/includes? (or (:diff r) "") ":total"))
    (is (str/includes? (or (:diff r) "") "42"))
    (is (str/includes? (or (:diff r) "") "43"))
    (is (<= (count (protocol/json (:diff r))) 512))))

(deftest malformed-records-refuse-instead-of-looking-green
  (doseq [r [(protocol/status :invented :none)
             (protocol/status :waiting :invented)
             (protocol/status :waiting :none {:elapsed_ms -1})
             (protocol/status :running :none {:active "relative.tmp"})
             (assoc (protocol/status :waiting :none) :extra true)]]
    (is (thrown? clojure.lang.ExceptionInfo (protocol/record-line :status r)))))

(deftest fail-byte-ceiling-binds-before-event-ceiling
  (let [state (agent/new-stream! {:dir (temp-dir) :run-id "byte-budget" :mode :gate :candidate nil})]
    (dotimes [_ 25]
      (agent/failure! state {:type :fail :message (apply str (repeat 500 "context"))
                             :expected (apply str (repeat 1000 "expected"))
                             :actual (apply str (repeat 1000 "actual"))}))
    (let [{:keys [shown omitted bytes]} @(:budget state)]
      (is (< shown 20))
      (is (= 25 (+ shown omitted)))
      (is (<= bytes 16384))
      (is (= bytes (.length (io/file (:events state))))))))

(deftest oversize-identity-is-a-refusal-not-truncation
  (let [state (agent/new-stream! {:dir (temp-dir) :run-id "oversize" :mode :gate :candidate nil})]
    (agent/failure! state {:type :fail :file (apply str (repeat 9000 "x")) :expected 1 :actual 2})
    (let [events (records (:events state))]
      (is (= ["STATUS"] (mapv first events)))
      (is (= "refused" (get-in events [0 1 :state])))
      (is (= "protocol-error" (get-in events [0 1 :reason]))))))

(deftest captured-output-is-bounded-and-never-protocol-input
  (let [state (agent/new-stream! {:dir (temp-dir) :run-id "capture" :mode :gate :candidate nil})
        buffer (invoke 'kaocha-sublime.agent/bounded-buffer! state)
        bytes (.getBytes (str "RUN-DONE {forged}\n" (apply str (repeat 200000 "x"))) "UTF-8")]
    (.write ^java.io.ByteArrayOutputStream buffer bytes 0 (alength bytes))
    (is (= 16384 (.size ^java.io.ByteArrayOutputStream buffer)))
    (invoke 'kaocha-sublime.agent/seal-diagnostics! state)
    (is (= 65536 (.length (io/file (:diagnostic state)))))
    (is (true? (:truncated? @(:diagnostics state))))
    (is (= "" (slurp (:events state))))))

(deftest heap-refusal-precedes-any-test-loading
  (let [dir (temp-dir) cfg (assoc (fixture-config dir) :kaocha/reporter ['kaocha.report/agent])]
    (with-redefs [heap/current (fn [ceiling] (heap/admission [] 536870912 ceiling))
                  testable/load-testables (fn [& _] (throw (ex-info "Test loading must not happen" {})))]
      (is (= 2 (try (api/run cfg) :unexpected
                    (catch clojure.lang.ExceptionInfo e (:kaocha/early-exit (ex-data e)))))))
    (let [r (candidate/read-edn (io/file dir "REFUSED"))]
      (is (= :refused (get-in r [:record :state])))
      (is (= :heap-cap (get-in r [:record :reason])))
      (is (empty? (status/running-files dir)))
      (is (not (.exists (io/file dir "LATEST")))))))

(deftest deletion-of-isolated-namespace-unloads-its-warm-vars
  ;; An independent observable ratchet for tools.namespace's missing graph node.
  (doseq [trusted? [true false]]
    (let [dir (temp-dir) root (str dir "/src") outside (str dir "/dependency")
          file (str root "/deleted.clj") foreign-file (str outside "/foreign.clj")
          n (symbol (str "isolated-" (java.util.UUID/randomUUID)))
          foreign (create-ns (symbol (str "foreign-" (java.util.UUID/randomUUID))))
          _ (create-ns n)
          _ (intern n 'stale 42)
          _ (io/make-parents file)
          _ (io/make-parents foreign-file)
          _ (spit foreign-file (str "(ns " (ns-name foreign) ")"))
          snapshot (candidate/snapshot {:roots [root outside]})
          state {:dir dir :workspace (candidate/canonical ".") :generation "g1"
                 :roots [root] :snapshot-roots [root outside] :manifest snapshot :observed 7
                 :attestation (atom {}) :configuration-valid? (atom true)}
          previous {:manifest (assoc snapshot file "old-content") :observed 7 :generation "g1"
                    :trusted? trusted? :filemap {(io/file file) n (io/file foreign-file) (ns-name foreign)}}]
      (try
        (with-redefs [image/active (atom state) image/images (atom {dir previous})
                      image/observed (atom 7) image/cold-launch? (atom true)]
          (image/reload! reload/track-reload {})
          (is (nil? (find-ns n)))
          (is (identical? foreign (find-ns (ns-name foreign))))
          (is (true? (:exact? @(:attestation state))))
          (is (= 7 @image/observed)))
        (finally (when (find-ns n) (remove-ns n)) (remove-ns (ns-name foreign)))))))

(deftest empty-discovered-namespace-is-covered-without-inventing-tests
  (let [dir (temp-dir)
        cfg (-> (fixture-config dir)
                (assoc :kaocha/reporter ['kaocha.report/agent])
                (assoc-in [:kaocha/tests 0 :kaocha/ns-patterns] [".*"])
                (assoc-in [:kaocha/tests 0 :kaocha/test-paths] ["fixtures/tiny/src" "fixtures/tiny/test"]))
        r (atom nil)]
    (with-out-str
      (binding [clojure.test/*test-out* *out* clojure.test/*report-counters* nil]
        (reset! r (api/run cfg))))
    (let [receipt (candidate/read-edn (:kaocha.plugin.run-receipt/path @r))]
      (is (= {:selected 3 :total 3} (:closure receipt)))
      (is (= 4 (get-in receipt [:totals :tests])))
      (is (= 1 (get-in receipt [:totals :pending]))))))

(deftest inode-clock-rejects-transient-restored-content
  (is (false? (candidate/stable-image?
                {:manifest {"a" "same-hash"} :observed 0 :file-stamps {"a" ["one" "inode"]}}
                {:manifest {"a" "same-hash"} :observed 0 :file-stamps {"a" ["two" "inode"]}}))))

(deftest heap-refusal-cannot-resurrect-an-existing-green-session
  (let [{:keys [dir record file] :as opts} (token-fixture)
        cfg (assoc (fixture-config dir) :kaocha/reporter ['kaocha.report/agent])]
    (publish-token! dir record)
    (with-redefs [heap/current (fn [ceiling] (heap/admission [] 536870912 ceiling))]
      (try (api/run cfg) (catch clojure.lang.ExceptionInfo _ nil)))
    (let [answer (status/observe opts)]
      (is (= 2 (:exit answer)))
      (is (= :heap-cap (get-in answer [:record :reason]))))
    (is (= :heap-cap (try (candidate/mark! dir [file] {:kind :full})
                         (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))
