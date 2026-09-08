(ns kaocha.plugin.run-receipt
  "Atomic per-run EDN receipts for Kaocha 1.91.1392."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [kaocha.api :as api]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.plugin.capture-output :as capture]
            [kaocha.plugin.filter :as filter]
            [kaocha.result :as result]
            [kaocha.testable :as testable]
            [kaocha.watch :as watch]
            [kaocha-sublime.agent :as agent]
            [kaocha-sublime.candidate :as candidate]
            [kaocha-sublime.heap :as heap]
            [kaocha-sublime.image :as image]
            [kaocha-sublime.protocol :as protocol]
            [kaocha-sublime.reap :as reap]
            [kaocha-sublime.status :as status]
            [slingshot.slingshot :refer [throw+]])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files StandardCopyOption OpenOption StandardOpenOption)
           (java.nio.channels FileChannel)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util UUID Properties)))

(def plugin-version "0.1.0-SNAPSHOT")

(defn- edn-str [x]
  (binding [*print-length* nil *print-level* nil *print-readably* true
            *print-dup* false *print-meta* false *print-namespace-maps* false]
    (pr-str x)))

(defn sha256 [^bytes bytes]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- canonical [x]
  (cond
    (map? x) (into (sorted-map-by #(compare (edn-str %1) (edn-str %2)))
                  (map (fn [[k v]] [k (canonical v)])) x)
    (set? x) (into (sorted-set-by #(compare (edn-str %1) (edn-str %2))) (map canonical) x)
    (sequential? x) (mapv canonical x)
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x) (symbol? x)) x
    :else (str "<" (.getName (class x)) ">")))

(defn config-hash [config]
  (sha256 (.getBytes (edn-str (canonical config)) "UTF-8")))

(defn kaocha-version []
  (with-open [r (io/reader (io/resource "META-INF/maven/lambdaisland/kaocha/pom.properties"))]
    (let [p (Properties.)] (.load p r) (.getProperty p "version"))))

(def ^:dynamic *watch-events* nil)
(def ^:dynamic *waiting-for-edit?* false)
(def ^:dynamic *accepted-event* nil)
(def ^:dynamic *reserve-after-drain!* nil)

(declare begin!)

(def ^:dynamic *run-state* nil)
(def ^:dynamic *run-observer* nil)

;; INTENT: AGENT-LEASE-014
(defn- release! [state]
  (when-let [{:keys [lock channel released?]} (:lease state)]
    (when (compare-and-set! released? false true)
      (try
        (when (.isValid ^java.nio.channels.FileLock lock) (.release ^java.nio.channels.FileLock lock))
        (finally
          (try (.close ^FileChannel channel)
               (finally (.unlock image/run-lock)))))))
  (when (= (:run-id state) (:run-id @image/active)) (reset! image/active nil)))

(defn- cleanup! [state]
  (when state
    (try
      (when (and (:events state) (not @(:committed? state)))
        (swap! image/images update (:dir state) assoc :trusted? false)
        (agent/refuse! state :protocol-error)
        ;; REFUSED is the durable terminal evidence for this aborted run.
        (Files/deleteIfExists (.toPath ^java.io.File (:tmp state))))
      (finally (release! state)))))

(defn- guarded-run [f config]
  (binding [*run-state* (atom (get-in config [:kaocha.watch/tracker ::pending-state]))]
    (try (f config)
         (finally
           (try (cleanup! @*run-state*)
                (finally (when *run-observer* (reset! *run-observer* @*run-state*))))))))

(defn- adapt! [v wrap]
  (alter-var-root v #(if (::watch-adapter (meta %)) %
                        (with-meta (wrap %) {::watch-adapter true}))))

(defn- event-files [events]
  (->> events (remove keyword?) (map #(-> % str io/file .getCanonicalPath))
       distinct sort vec))

;; INTENT: STATUS-TRIGGER-003
(defn- install-watch-adapter! []
  (adapt! #'api/run (fn [f] (fn [config] (guarded-run f config))))
  ;; A direct api/run may load this plugin from inside its first invocation.
  ;; Its already-entered function cannot acquire the newly installed wrapper.
  ;; Refuse before reservation; normal CLI/REPL config loads plugins first.
  (adapt! #'watch/run-loop
          (fn [f] (fn [& args]
                    (try (apply f args)
                         (finally
                           (when (.isHeldByCurrentThread image/run-lock)
                             (cleanup! @image/active)))))))
  (adapt! #'capture/make-buffer
          (fn [f] (fn [] (if-let [state @image/active] (agent/bounded-buffer! state) (f)))))
  (adapt! #'watch/qput
          (fn [f] (fn [q event]
                    (when-not (keyword? event) (swap! image/observed inc))
                    (f q event))))
  (adapt! #'watch/qtake
          (fn [f] (fn [q]
                    (let [event (f q)]
                      (when *accepted-event* (reset! *accepted-event* event))
                      event))))
  (adapt! #'watch/track-reload! (fn [f] (fn [tracker] (image/reload! f tracker))))
  ;; Observe the real accepted batch. Reservation and START happen after the
  ;; queue drain, before its caller can scan/reload anything, so START's trigger
  ;; prefix and omitted count refer to the same complete batch as the receipt.
  (adapt! #'watch/drain-queue!
          (fn [f] (fn [q]
                    (let [events (f q)]
                      (when *watch-events* (swap! *watch-events* into events))
                      (when *reserve-after-drain!* (*reserve-after-drain!* events))
                      events))))
  (adapt! #'watch/drain-and-rescan!
          (fn [f] (fn [q tracker paths]
                    (let [at (str (Instant/now)) pending (atom nil)
                          accepted (when *accepted-event* @*accepted-event*)
                          batch (fn [events] (event-files (cond-> events accepted (conj accepted))))]
                      (binding [*watch-events* (atom [])
                                *reserve-after-drain!*
                                (fn [events]
                                  (when-let [settings (::settings tracker)]
                                    (reset! pending
                                            (begin! (assoc settings :kaocha.watch/tracker
                                                           {::trigger {:files (batch events) :at at}
                                                            ::auto-followup? (not *waiting-for-edit?*)
                                                            ::last-path (::last-path tracker)})))))]
                        (try
                          (let [tracker (f q tracker paths)]
                            (assoc tracker ::trigger {:files (batch @*watch-events*) :at at}
                                           ::pending-state @pending
                                           ::auto-followup? (not *waiting-for-edit?*)))
                          (catch Throwable t (cleanup! @pending) (throw t))))))))
  (adapt! #'watch/wait-and-rescan!
          (fn [f] (fn [q tracker paths ignore]
                    (binding [*waiting-for-edit?* true *accepted-event* (atom nil)]
                      (let [[tracker event] (f q tracker paths ignore)]
                        [(-> tracker
                             (assoc ::auto-followup? false)
                             (update-in [::trigger :files]
                                        #(event-files (conj (vec %) event))))
                         event]))))))

(defn- install-summary-adapter! []
  ;; repl/run calls result/totals after throwing away the enclosing result map.
  ;; Preserve a path carried by receipt-tagged suite results, including first use
  ;; when this namespace is loaded from inside repl/run itself.
  (when-not (= "1.91.1392" (kaocha-version))
    (throw (ex-info "run-receipt requires Kaocha 1.91.1392 (summary adapter)" {})))
  (alter-var-root
    #'result/totals
    (fn [f]
      (if (::adapter (meta f)) f
          (with-meta
            (fn [tests]
              (let [r (f tests)
                    paths (set (keep ::path tests))]
                (cond-> r (= 1 (count paths)) (assoc ::path (first paths)))))
            {::adapter true})))))

(defn- git-sha []
  (try (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD")]
         (when (zero? exit) (str/trim out)))
       (catch Exception _ nil)))

;; INTENT: AGENT-REFUSAL-ID-016
(defn- new-run-id [prefix]
  (let [id (or (System/getenv "KAOCHA_RUN_ID") (str prefix "-" (UUID/randomUUID)))]
    (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,199}" id)
      (throw (ex-info "Unsafe KAOCHA_RUN_ID" {:run-id id :reason :protocol-error})))
    id))

(defn- agent-config [config]
  (let [reporters (:kaocha/reporter config)
        enabled? (or (::agent? config) (= "1" (System/getenv "KAOCHA_AGENT"))
                     (some #{'kaocha.report/agent} (if (vector? reporters) reporters [reporters])))]
    (if-not enabled? config
      (let [dir (candidate/canonical (::dir config "target/kaocha-runs/"))
            caps (heap/current (::heap-ceiling-bytes config 536870912))]
        (when-not (:admitted? caps)
          (let [state (agent/new-stream! {:dir dir :run-id (new-run-id "refused")
                                           :mode (image/mode config) :session image/session-id})]
            (when *run-observer* (reset! *run-observer* state))
            (candidate/publish-session! dir {:workspace (candidate/canonical ".")
                                              :session image/session-id
                                              :generation (candidate/data-hash [::config-hash :heap-cap])
                                              :refused :heap-cap})
            (agent/refuse! state :heap-cap)
            (candidate/atomic-edn! (io/file dir "HEAP-REFUSAL") caps)
            (throw+ {:kaocha/early-exit 2 :reason :heap-cap})))
        (let [session (image/session config (::config-hash config))]
          (assoc config ::agent? true ::agent-session session ::heap caps
                        :kaocha/reporter ['kaocha.report/agent]))))))

(defn- active-record [state]
  (merge (select-keys state [:run-id :started :trigger :auto-followup? :followup-of
                             :workspace :session :generation :candidate :mode :events :heap])
         {:status :running :phase :load :identity :unknown
          :jvm-pid (.pid (ProcessHandle/current))
          :process-start (str (.get (.startInstant (.info (ProcessHandle/current)))))}))

(defn- reserve-lock! [dir]
  (let [channel (FileChannel/open (.toPath (io/file dir "LOCK"))
                                  (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (try
      (if-let [lock (.tryLock channel)]
        (if (and (zero? (.getHoldCount image/run-lock)) (.tryLock image/run-lock))
          {:channel channel :lock lock :released? (atom false)}
          (do (.release lock) (.close channel)
              (throw (ex-info "Another invocation owns this JVM" {:reason :protocol-error}))))
        (do (.close channel) (throw (ex-info "Another run owns this image" {:reason :protocol-error}))))
      (catch Exception e (.close channel) (throw e)))))

(defn- begin! [config]
  (let [started (str (Instant/now))
        id (new-run-id (str/replace started #"[:.]" "-"))
        dir (.getCanonicalFile (io/file (::dir config "target/kaocha-runs/")))
        _ (Files/createDirectories (.toPath dir) (make-array java.nio.file.attribute.FileAttribute 0))
        path (io/file dir (str id ".edn"))
        tmp (io/file dir (str id ".edn.tmp"))
        _ (when (.exists path) (throw (ex-info "Receipt id already completed" {:path (str path)})))
        lease (when (::agent? config) (reserve-lock! dir))
        pending (atom nil)
        reserved? (atom false)]
    (try
      (let [dead (when lease (filterv #(= :dead (candidate/process-state %))
                                     (keep candidate/read-edn (status/running-files (str dir)))))
        _ (when (seq dead) (reap/reap! (str dir)))
        _ (Files/createFile (.toPath tmp) (make-array java.nio.file.attribute.FileAttribute 0))
        _ (reset! reserved? true)
        _ (when (.exists path)
            (Files/delete (.toPath tmp))
            (throw (ex-info "Receipt id completed while reserving it" {:path (str path)})))
        state {:run-id id :started started :nano (System/nanoTime)
               :tmp tmp :path path :committed? (atom false)
               :trigger (or (get-in config [:kaocha.watch/tracker ::trigger])
                            {:files [] :at started})
               :auto-followup? (true? (get-in config [:kaocha.watch/tracker ::auto-followup?]))
               :followup-of (get-in config [:kaocha.watch/tracker ::last-path])
               :config-hash (::config-hash config) :git-sha (git-sha)
               :reclaimed dead
               :heap (or (::heap config) (heap/current (::heap-ceiling-bytes config 536870912)))}]
    (if (::agent? config)
      (let [session (::agent-session config)
            snapshot (image/capture session)
            state (merge state session snapshot
                         {:dir (str dir) :mode (image/mode config) :heap (::heap config)
                          :lease lease :attestation (atom {}) :configuration-valid? (atom true)})
            state (assoc state :candidate (candidate/candidate-id state))
            state (agent/new-stream! state)]
        (reset! pending state)
        (when *run-state* (reset! *run-state* state))
        (reset! (:attestation state) {:exact? (image/admission-identity state)})
        (candidate/publish-session! dir session)
        (Files/deleteIfExists (.toPath (io/file dir "REFUSED")))
        (reset! image/active state)
        (spit tmp (edn-str (active-record state)))
        (agent/start! state)
        (doseq [owner dead]
          (agent/emit! state :status (protocol/status :killed :process-dead {:id (:run-id owner)})))
        state)
      (do (spit tmp (edn-str (active-record state))) state)))
      (catch Throwable t
        (try
          (cleanup! @pending)
          (finally
            (try
              (when @reserved? (Files/deleteIfExists (.toPath tmp)))
              (finally (release! {:lease lease})))))
        (throw t)))))

(defn- totals [r]
  (let [{:kaocha.result/keys [count pass fail error pending]} (result/testable-totals r)]
    {:tests count :assertions (+ pass fail error) :pass pass :fail fail :error error :pending pending}))

(defn- failure [e]
  (let [t (:kaocha/testable e) id (:kaocha.testable/id t)
        r (agent/failure-record {} e 1)]
    {:ns (or (some-> id namespace) (some-> (:kaocha.ns/name t) str))
     :var (when (and id (namespace id)) (name id))
     :message (:context r) :expected (:expected r) :actual (:actual r)
     :file (:file r) :line (:line r) :truncated (:truncated r) :unavailable (:unavailable r)}))

(defn- test-outcome [t]
  (let [{:keys [fail error pending tests]} (totals t)]
    {:id (:kaocha.testable/id t)
     :outcome (cond (pos? error) :error (pos? fail) :fail (pos? pending) :pending
                    (zero? tests) :skipped :else :pass)
     :stdout (:text (protocol/text-preview (get t :kaocha.plugin.capture-output/output "") 16384))}))

;; INTENT: STATUS-POINTER-001
(defn- publish-latest! [path receipt]
  (let [dir (.getParentFile ^java.io.File path)
        tmp (io/file dir (str "LATEST." (:run-id receipt) ".tmp"))]
    (spit tmp (str (edn-str (cond-> {:path (str path) :run-id (:run-id receipt)
                                    :finished (:finished receipt)}
                             (:schema receipt) (assoc :sha256 (candidate/file-hash path)))) "\n"))
    (Files/move (.toPath tmp) (.toPath (io/file dir "LATEST"))
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))))

(defn- children [node]
  (or (:kaocha.result/tests node) (:kaocha.test-plan/tests node) (:kaocha/tests node)))

;; Kaocha 1.91's test-seq-with-skipped recurses through test-seq, which drops
;; nested skips. Preserve the complete tree and inherited declared exclusions.
;; INTENT: AGENT-DECLARED-GATE-015
(defn- matches-filter? [node ids metadata]
  (and (:kaocha.testable/id node) (filter/matches? node ids metadata)))

;; INTENT: AGENT-REQUESTED-SCOPE-020
(defn- coverage-tree
  ([node]
   (coverage-tree node {} false [] [] false (set (::probe-skipped-suites node))))
  ([node inherited skipped? matched-meta matched-ids outside-suite? probe-suites]
   (let [local (cond-> (filter/filters node)
                 (contains? node ::configured-skip) (assoc :skip (::configured-skip node)))
         rules (filter/merge-filters inherited local)
         matched-meta (vec (distinct (concat matched-meta
                                    (filter #(get (:kaocha.testable/meta node) (keyword %)) (:skip-meta rules)))))
         matched-ids (vec (distinct (concat matched-ids
                                   (filter #(matches-filter? node [%] []) (:skip rules)))))
         skipped? (or skipped? (:kaocha.testable/skip node))
         outside-suite? (or outside-suite? (contains? probe-suites (:kaocha.testable/id node)))
         focused? (matches-filter? node (:focus rules) (:focus-meta rules))
         outside-focus? (or outside-suite?
                            (and (or (seq (:focus rules)) (seq (:focus-meta rules))) (not focused?)))
         rules (if focused? (dissoc rules :focus :focus-meta) rules)]
     (assoc node ::skipped? (boolean skipped?)
                 ::outside-focus? (boolean outside-focus?)
                 ::declared-skip (when skipped? matched-meta)
                 ::declared-skip-ids (when skipped? matched-ids)
                 ::children (mapv #(coverage-tree % rules skipped? matched-meta matched-ids outside-suite? probe-suites)
                                  (children node))))))

(defn- coverage-nodes [tree] (tree-seq #(seq (::children %)) ::children tree))

(defn- coverage-data [r]
  (let [nodes (coverage-nodes (coverage-tree r))
        namespaces (filter #(= :kaocha.type/ns (:kaocha.testable/type %)) nodes)
        leaves-of #(filter hierarchy/leaf? (coverage-nodes %))
        declared? #(or (seq (::declared-skip %)) (seq (::declared-skip-ids %)))
        selected (filter #(or (declared? %)
                              (and (not (::skipped? %))
                                   (or (contains? % :kaocha.result/tests)
                                       (every? declared? (leaves-of %))))) namespaces)
        complete? (fn [n probe?]
                    (let [leaves (leaves-of n)]
                      (and (not (:kaocha.testable/load-error n))
                           (every? #(and (not (:kaocha.testable/load-error %))
                                         (or (declared? %)
                                             (and probe? (::outside-focus? %))
                                             (and (not (::skipped? %))
                                                  (pos? (:kaocha.result/count % 0))
                                                  (zero? (:kaocha.result/pending % 0))))) leaves))))
        full (filter #(complete? % false) selected)
        probe? (contains? r ::probe-targets)
        requested (:kaocha.filter/focus r)
        required-complete? (if probe?
                             (every? (fn [target]
                                       (let [matches (filter #(matches-filter? % [target] []) nodes)]
                                         (and (seq matches) (every? #(complete? % true) matches))))
                                     requested)
                             (= (count full) (count selected)))
        leaves (filter hierarchy/leaf? nodes)
        executed (filter #(and (not (::skipped? %)) (pos? (:kaocha.result/count % 0))
                               (zero? (:kaocha.result/pending % 0))) leaves)
        pending (filter #(and (not (::skipped? %)) (pos? (:kaocha.result/pending % 0))) leaves)
        unexecuted (filter #(or (::skipped? %) (and (zero? (:kaocha.result/count % 0))
                                                   (zero? (:kaocha.result/pending % 0)))) leaves)
        declared-skips (filter declared? unexecuted)
        focus-skips (filter #(and (not (declared? %)) (::outside-focus? %)) unexecuted)]
    {;; INTENT: AGENT-CLOSURE-BASIS-022
     :closure-basis :discovered-namespaces-v1
     :scope (cond probe? {:kind :probe :requested (mapv str (::probe-targets r))}
                  (= (count selected) (count namespaces)) {:kind :full :requested []}
                  :else {:kind :namespaces :requested (mapv (comp str :kaocha.ns/name) selected)})
     :focus-rules {:focus (vec (:kaocha.filter/focus r))
                   :focus-meta (vec (:kaocha.filter/focus-meta r))
                   :skip-suites (vec (::probe-skipped-suites r))}
     :closure (when (seq namespaces) {:selected (count selected) :total (count namespaces)})
     :executed-namespaces (mapv (comp str :kaocha.ns/name) selected)
     :partial-namespaces (mapv (comp str :kaocha.ns/name) (remove #(contains? (set (map :kaocha.testable/id full)) (:kaocha.testable/id %)) selected))
     :coverage (cond (empty? selected) :unknown required-complete? :complete :else :partial)
     :skip-rules (mapv (fn [n] (cond-> {:id (:kaocha.testable/id n) :skip-meta (vec (:kaocha.filter/skip-meta n))}
                               (seq (:kaocha.filter/skip n)) (assoc :skip (vec (:kaocha.filter/skip n)))))
                       (filter #(or (seq (:kaocha.filter/skip-meta %)) (seq (:kaocha.filter/skip %)))
                               (map #(cond-> % (contains? % ::configured-skip)
                                       (assoc :kaocha.filter/skip (::configured-skip %))) nodes)))
     :declared-skips (mapv (fn [n] (cond-> {:id (:kaocha.testable/id n) :skip-meta (::declared-skip n)}
                                    (seq (::declared-skip-ids n)) (assoc :skip (::declared-skip-ids n))))
                           declared-skips)
     :executed-tests (count executed)
     :skipped-tests (count declared-skips)
     :skipped-by-focus (count focus-skips)
     :unexecuted-tests (- (count leaves) (count executed) (count pending)
                          (count declared-skips) (count focus-skips))}))

;; INTENT: AGENT-PARENT-OUTCOME-019
(defn- parent-evidence [path]
  ;; Bind the identity, digest and verdict to one read of the committed parent.
  ;; Store this in the child so replay never depends on the parent's retention.
  (try
    (let [bytes (Files/readAllBytes (.toPath (io/file path)))
          parent (edn/read-string (String. bytes "UTF-8"))
          digest (sha256 bytes)
          done (protocol/done-record parent path digest)]
      {:parent (:run-id parent) :parent-sha256 digest
       :parent-outcome (if (and (= 1 (:schema parent)) (:finished parent)
                                (every? integer? (map done [:fail :error]))
                                (protocol/valid-record? :done done))
                         (select-keys done [:outcome :fail :error])
                         protocol/unknown-parent-outcome)})
    (catch Exception _
      {:parent nil :parent-sha256 nil :parent-outcome protocol/unknown-parent-outcome})))

(defn- finish! [r]
  (let [{:keys [run-id started nano tmp path committed? config-hash git-sha
                trigger auto-followup? followup-of] :as state} (::state r)
        nodes (testable/test-seq-with-skipped r)
        coverage (when (:events state) (coverage-data r))
        diagnostic (when (:events state) (agent/seal-diagnostics! state))
        identity (when (:events state) (image/finish-identity! state))
        receipt (cond-> {:run-id run-id :started started :finished (str (Instant/now))
                         :wall-ms (/ (- (System/nanoTime) nano) 1e6)
                         :trigger trigger :auto-followup? auto-followup?
                         :totals (totals r)
                         :failures (mapv failure (filter hierarchy/fail-type?
                                                        (mapcat :kaocha.testable/events nodes)))
                         :config-hash config-hash :heap (:heap state) :jvm-pid (.pid (ProcessHandle/current))
                         :kaocha-version (kaocha-version) :plugin-version plugin-version}
                  (:events state)
                  (merge (select-keys state [:workspace :session :generation :candidate :manifest
                                              :observed :file-stamps :mode :events :heap :classpath-generation])
                         (dissoc coverage :executed-tests :skipped-tests :skipped-by-focus)
                         {:schema 1 :identity identity :diagnostic diagnostic
                          :identity-proof (select-keys @(:attestation state)
                                                       [:base-valid? :changed-covered? :reload-stable? :reload-observed
                                                        :scheduled :changed :finish-observed :disk-stable? :file-stamps-stable? :classpath-stable? :configuration-valid?])
                          :process-start (:process-start (active-record state))
                          :failure-budget @(:budget state) :failures @(:failures state)
                          :totals (assoc (totals r) :tests (:executed-tests coverage) :skipped (:skipped-tests coverage)
                                                   :skipped-by-focus (:skipped-by-focus coverage))}
                         (if auto-followup? (parent-evidence followup-of)
                             {:parent nil :parent-sha256 nil}))
                  git-sha (assoc :git-sha git-sha)
                  (and auto-followup? followup-of) (assoc :followup-of followup-of)
                  (:kaocha.plugin.affected/selection r)
                  (assoc :affected (:kaocha.plugin.affected/selection r))
                  (::include-tests? r)
                  (assoc :tests (mapv test-outcome (filter #(= :kaocha.type/var (:kaocha.testable/type %)) nodes))))]
    ;; Keep the running reservation (and its PID) through BOTH publications.
    ;; Killing between receipt rename and LATEST rename must not expose old green.
    (let [ready (io/file (str path ".ready"))
          bytes (.getBytes (str (edn-str receipt) "\n") "UTF-8")]
      ;; INTENT: AGENT-ARITHMETIC-018
      ;; Validate the exact projection before any receipt or pointer publication.
      ;; record-line is unconditional, including when Clojure *assert* is false.
      (when (:events state)
        (protocol/record-line :done (protocol/done-record receipt path (sha256 bytes))))
      (Files/write (.toPath ready) bytes (make-array OpenOption 0))
      (Files/move (.toPath ready) (.toPath path) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE])))
    (publish-latest! path receipt)
    (Files/delete (.toPath tmp))
    (reset! committed? true)
    ;; The outer run finally also covers Kaocha's subsequent summary callback.
    (when (:events state) (agent/done! state receipt))
    (-> (cond-> r (:kaocha.watch/tracker r)
          (update :kaocha.watch/tracker
                  #(-> % (dissoc ::pending-state)
                       (assoc ::last-path (str path)
                              ::settings (select-keys r [::dir ::config-hash ::agent? ::agent-session ::heap :kaocha/tests])))))
        (assoc ::path (str path))
        (update :kaocha.result/tests #(mapv (fn [t] (assoc t ::path (str path))) %)))))

(defplugin kaocha.plugin/run-receipt
  (config [config]
    (install-summary-adapter!)
    (install-watch-adapter!)
    (let [config (assoc config ::config-hash
                        (config-hash (dissoc config ::config-hash ::state :kaocha.watch/tracker
                                             :kaocha.plugin.affected/decision :kaocha.plugin.affected/snapshot
                                             ::agent-session ::heap ::agent? :kaocha.plugin.randomize/seed
                                             :kaocha.watch/focus :kaocha.filter/focus)))
          config (agent-config config)]
      (when (and (::agent? config) (nil? *run-state*)
                 (some #(str/starts-with? (.getClassName ^StackTraceElement %) "kaocha.api$run")
                       (.getStackTrace (Thread/currentThread))))
        (let [state (agent/new-stream! {:dir (candidate/canonical (::dir config "target/kaocha-runs/"))
                                       :run-id (new-run-id "refused")
                                       :session image/session-id})]
          (agent/refuse! state :protocol-error)
          (throw+ {:kaocha/early-exit 2 :reason :protocol-error
                   :message "Require kaocha.plugin.run-receipt before the first direct kaocha.api/run"})))
      ;; Reuse the reservation made when watch accepted the event, before rescan.
      ;; Initial watch runs reserve here, before reload. CLI config has no tracker.
      (if-let [tracker (:kaocha.watch/tracker config)]
        (assoc config ::state
               (if-let [pending (::pending-state tracker)]
                 (do
                   (when (:events pending)
                     (reset! (:configuration-valid? pending)
                             (and (= (:generation pending) (get-in config [::agent-session :generation]))
                                  (= (:session pending) (get-in config [::agent-session :session])))))
                   (assoc pending :trigger (::trigger tracker)
                                  :auto-followup? (true? (::auto-followup? tracker))
                                  :config-hash (::config-hash config)))
                 (begin! config)))
        (if (and (::agent? config)
                 (some #(clojure.string/starts-with? (.getClassName ^StackTraceElement %) "kaocha.api$run")
                       (.getStackTrace (Thread/currentThread))))
          (assoc config ::state (begin! config)) config))))
  (pre-load [config]
    (let [state (or (::state config) (begin! config))
          reporter (:kaocha/reporter config)]
      (when-let [namespaces (::probe-namespaces config)]
        (try (image/reload-probe! namespaces)
             (catch Throwable t
               (when (:events state)
                 (agent/failure! state {:type :error :actual t
                                        :kaocha/testable {:kaocha.testable/load-error t}})
                 ;; INTENT: AGENT-LOAD-REASON-021
                 ;; Close with the load diagnosis before finally releases the lease.
                 ;; An unreadable namespace is not an unreadable protocol record.
                 (agent/refuse! state :load-error))
               (throw t))))
      (assoc config ::state state
             :kaocha/reporter
             (fn [event]
               (binding [agent/*stream* (when (:events state) state)] (reporter event))
               (when (and (not (:events state)) (= :summary (:type event)) @(:committed? state))
                 (println)
                 (println "RUN-RECEIPT" (str (:path state))) (flush))))))
  (pre-run [plan]
    (when-let [state (when (:events (::state plan)) (::state plan))]
      (candidate/atomic-edn! (:tmp state)
                            (assoc (active-record state) :phase :run
                                   :identity (if (:exact? @(:attestation state)) :exact :unknown))))
    plan)
  (post-run [r] (finish! r)))

;; Install before the first api/run when callers explicitly require the plugin.
(install-watch-adapter!)
