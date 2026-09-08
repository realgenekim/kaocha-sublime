(ns kaocha.plugin.run-receipt
  "Atomic per-run EDN receipts for Kaocha 1.91.1392."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.result :as result]
            [kaocha.testable :as testable]
            [kaocha.watch :as watch])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files StandardCopyOption OpenOption)
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

(declare begin!)

(defn- adapt! [v wrap]
  (alter-var-root v #(if (::watch-adapter (meta %)) %
                        (with-meta (wrap %) {::watch-adapter true}))))

(defn- event-files [events]
  (->> events (remove keyword?) (map #(-> % str io/file .getCanonicalPath))
       distinct sort vec))

;; INTENT: STATUS-TRIGGER-003
(defn- install-watch-adapter! []
  ;; Kaocha 1.91.1392 discards both the drained events and the run-loop branch.
  ;; These wrappers observe its real queue; they neither manufacture events nor
  ;; change reload/selection. Only run-loop's automatic branch rescans outside wait.
  (adapt! #'watch/drain-queue!
          (fn [f] (fn [q]
                    (let [events (f q)]
                      (when *watch-events* (swap! *watch-events* into events))
                      events))))
  (adapt! #'watch/drain-and-rescan!
          (fn [f] (fn [q tracker paths]
                    (binding [*watch-events* (atom [])]
                      (let [at (str (Instant/now))
                            pending (when-let [settings (::settings tracker)]
                                      (begin! (assoc settings :kaocha.watch/tracker
                                                     {::trigger {:files [] :at at}
                                                      ::auto-followup? (not *waiting-for-edit?*)
                                                      ::last-path (::last-path tracker)})))
                            tracker (f q tracker paths)]
                        (assoc tracker ::trigger {:files (event-files @*watch-events*) :at at}
                               ::pending-state pending
                               ::auto-followup? (not *waiting-for-edit?*)))))))
  (adapt! #'watch/wait-and-rescan!
          (fn [f] (fn [q tracker paths ignore]
                    (binding [*waiting-for-edit?* true]
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

(defn- begin! [config]
  (let [started (str (Instant/now))
        id (or (System/getenv "KAOCHA_RUN_ID")
               (str (str/replace started #"[:.]" "-") "-" (UUID/randomUUID)))
        _ (when-not (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,199}" id)
            (throw (ex-info "Unsafe KAOCHA_RUN_ID" {:run-id id})))
        dir (.getCanonicalFile (io/file (::dir config "target/kaocha-runs/")))
        _ (Files/createDirectories (.toPath dir) (make-array java.nio.file.attribute.FileAttribute 0))
        path (io/file dir (str id ".edn"))
        tmp (io/file dir (str id ".edn.tmp"))
        _ (when (.exists path) (throw (ex-info "Receipt id already completed" {:path (str path)})))
        _ (Files/createFile (.toPath tmp) (make-array java.nio.file.attribute.FileAttribute 0))
        _ (when (.exists path)
            (Files/delete (.toPath tmp))
            (throw (ex-info "Receipt id completed while reserving it" {:path (str path)})))
        state {:run-id id :started started :nano (System/nanoTime)
               :tmp tmp :path path :committed? (atom false)
               :trigger (or (get-in config [:kaocha.watch/tracker ::trigger])
                            {:files [] :at started})
               :auto-followup? (true? (get-in config [:kaocha.watch/tracker ::auto-followup?]))
               :followup-of (get-in config [:kaocha.watch/tracker ::last-path])
               :config-hash (::config-hash config) :git-sha (git-sha)}]
    (spit tmp (edn-str (assoc (select-keys state [:run-id :started :trigger :auto-followup? :followup-of])
                             :status :running :jvm-pid (.pid (ProcessHandle/current))
                             :process-start (str (.get (.startInstant (.info (ProcessHandle/current))))))))
    state))

(defn- totals [r]
  (let [{:kaocha.result/keys [count pass fail error pending]} (result/testable-totals r)]
    {:tests count :assertions (+ pass fail error) :pass pass :fail fail :error error :pending pending}))

(defn- failure [e]
  (let [t (:kaocha/testable e)
        id (:kaocha.testable/id t)
        m (:kaocha.testable/meta t)]
    {:ns (or (some-> id namespace) (some-> (:kaocha.ns/name t) str))
     :var (when (and id (namespace id)) (name id))
     :message (some-> (:message e) str)
     :expected (edn-str (:expected e))
     :actual (edn-str (if (instance? Throwable (:actual e))
                       (Throwable->map (:actual e)) (:actual e)))
     :file (some-> (or (:file e) (:file m) (:kaocha.testable/load-error-file t)) str)
     :line (or (:line e) (:line m) (:kaocha.testable/load-error-line t))}))

(defn- test-outcome [t]
  (let [{:keys [fail error pending tests]} (totals t)]
    {:id (:kaocha.testable/id t)
     :outcome (cond (pos? error) :error (pos? fail) :fail (pos? pending) :pending
                    (zero? tests) :skipped :else :pass)
     :stdout (get t :kaocha.plugin.capture-output/output "")}))

;; INTENT: STATUS-POINTER-001
(defn- publish-latest! [path receipt]
  (let [dir (.getParentFile ^java.io.File path)
        tmp (io/file dir (str "LATEST." (:run-id receipt) ".tmp"))]
    (spit tmp (str (edn-str {:path (str path) :run-id (:run-id receipt)
                           :finished (:finished receipt)}) "\n"))
    (Files/move (.toPath tmp) (.toPath (io/file dir "LATEST"))
                (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                StandardCopyOption/REPLACE_EXISTING]))))

(defn- finish! [r]
  (let [{:keys [run-id started nano tmp path committed? config-hash git-sha
                trigger auto-followup? followup-of]} (::state r)
        nodes (testable/test-seq-with-skipped r)
        receipt (cond-> {:run-id run-id :started started :finished (str (Instant/now))
                         :wall-ms (/ (- (System/nanoTime) nano) 1e6)
                         :trigger trigger :auto-followup? auto-followup?
                         :totals (totals r)
                         :failures (mapv failure (filter hierarchy/fail-type?
                                                        (mapcat :kaocha.testable/events nodes)))
                         :config-hash config-hash :jvm-pid (.pid (ProcessHandle/current))
                         :kaocha-version (kaocha-version) :plugin-version plugin-version}
                  git-sha (assoc :git-sha git-sha)
                  (and auto-followup? followup-of) (assoc :followup-of followup-of)
                  (:kaocha.plugin.affected/selection r)
                  (assoc :affected (:kaocha.plugin.affected/selection r))
                  (::include-tests? r)
                  (assoc :tests (mapv test-outcome (filter #(= :kaocha.type/var (:kaocha.testable/type %)) nodes))))]
    ;; Keep the running reservation (and its PID) through BOTH publications.
    ;; Killing between receipt rename and LATEST rename must not expose old green.
    (let [ready (io/file (str path ".ready"))]
      (Files/write (.toPath ready) (.getBytes (str (edn-str receipt) "\n") "UTF-8") (make-array OpenOption 0))
      (Files/move (.toPath ready) (.toPath path) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE])))
    (publish-latest! path receipt)
    (Files/delete (.toPath tmp))
    (reset! committed? true)
    (-> (cond-> r (:kaocha.watch/tracker r)
          (update :kaocha.watch/tracker
                  #(-> % (dissoc ::pending-state)
                       (assoc ::last-path (str path)
                              ::settings (select-keys r [::dir ::config-hash])))))
        (assoc ::path (str path))
        (update :kaocha.result/tests #(mapv (fn [t] (assoc t ::path (str path))) %)))))

(defplugin kaocha.plugin/run-receipt
  (config [config]
    (install-summary-adapter!)
    (install-watch-adapter!)
    (let [config (assoc config ::config-hash
                        (config-hash (dissoc config ::config-hash ::state :kaocha.watch/tracker
                                             :kaocha.plugin.affected/decision :kaocha.plugin.affected/snapshot)))]
      ;; Reuse the reservation made when watch accepted the event, before rescan.
      ;; Initial watch runs reserve here, before reload. CLI config has no tracker.
      (if-let [tracker (:kaocha.watch/tracker config)]
        (assoc config ::state
               (if-let [pending (::pending-state tracker)]
                 (assoc pending :trigger (::trigger tracker)
                                :auto-followup? (true? (::auto-followup? tracker))
                                :config-hash (::config-hash config))
                 (begin! config)))
        config)))
  (pre-load [config]
    (let [state (or (::state config) (begin! config))
          reporter (:kaocha/reporter config)]
      (assoc config ::state state
             :kaocha/reporter
             (fn [event]
               (reporter event)
               (when (and (= :summary (:type event)) @(:committed? state))
                 (println)
                 (println "RUN-RECEIPT" (str (:path state))) (flush))))))
  (post-run [r] (finish! r)))
