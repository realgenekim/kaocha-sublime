(ns kaocha.plugin.run-receipt
  "Atomic per-run EDN receipts for Kaocha 1.91.1392."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.result :as result]
            [kaocha.testable :as testable])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files StandardCopyOption OpenOption)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util UUID Properties)))

(def plugin-version "0.1.0-SNAPSHOT")

(defn sha256 [^bytes bytes]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- canonical [x]
  (cond
    (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                  (map (fn [[k v]] [k (canonical v)])) x)
    (set? x) (vec (sort-by pr-str (map canonical x)))
    (sequential? x) (mapv canonical x)
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x) (symbol? x)) x
    :else (str "<" (.getName (class x)) ">")))

(defn config-hash [config]
  (sha256 (.getBytes (pr-str (canonical config)) "UTF-8")))

(defn kaocha-version []
  (with-open [r (io/reader (io/resource "META-INF/maven/lambdaisland/kaocha/pom.properties"))]
    (let [p (Properties.)] (.load p r) (.getProperty p "version"))))

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
        state {:run-id id :started started :nano (System/nanoTime)
               :tmp tmp :path path :committed? (atom false)
               :config-hash (::config-hash config) :git-sha (git-sha)}]
    (spit tmp (pr-str (select-keys (assoc state :status :running) [:run-id :started :status])))
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
     :message (:message e)
     :expected (binding [*print-length* nil *print-level* nil] (pr-str (:expected e)))
     :actual (binding [*print-length* nil *print-level* nil]
               (pr-str (if (instance? Throwable (:actual e))
                         (Throwable->map (:actual e)) (:actual e))))
     :file (or (:file e) (:file m)) :line (or (:line e) (:line m))}))

(defn- test-outcome [t]
  (let [{:keys [fail error pending tests]} (totals t)]
    {:id (:kaocha.testable/id t)
     :outcome (cond (pos? error) :error (pos? fail) :fail (pos? pending) :pending
                    (zero? tests) :skipped :else :pass)
     :stdout (get t :kaocha.plugin.capture-output/output "")}))

(defn- finish! [r]
  (let [{:keys [run-id started nano tmp path committed? config-hash git-sha]} (::state r)
        nodes (testable/test-seq-with-skipped r)
        receipt (cond-> {:run-id run-id :started started :finished (str (Instant/now))
                         :wall-ms (/ (- (System/nanoTime) nano) 1e6)
                         :totals (totals r)
                         :failures (mapv failure (filter #(contains? #{:fail :error} (:type %))
                                                        (mapcat :kaocha.testable/events nodes)))
                         :config-hash config-hash :jvm-pid (.pid (ProcessHandle/current))
                         :kaocha-version (kaocha-version) :plugin-version plugin-version}
                  git-sha (assoc :git-sha git-sha)
                  (:kaocha.plugin.affected/selection r)
                  (assoc :affected (:kaocha.plugin.affected/selection r))
                  (::include-tests? r)
                  (assoc :tests (mapv test-outcome (filter #(= :kaocha.type/var (:kaocha.testable/type %)) nodes))))]
    (Files/write (.toPath tmp) (.getBytes (str (pr-str receipt) "\n") "UTF-8") (make-array OpenOption 0))
    (Files/move (.toPath tmp) (.toPath path) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (reset! committed? true)
    (-> r (assoc ::path (str path))
        (update :kaocha.result/tests #(mapv (fn [t] (assoc t ::path (str path))) %)))))

(defplugin kaocha.plugin/run-receipt
  (config [config]
    (install-summary-adapter!)
    (assoc config ::config-hash
           (config-hash (dissoc config ::config-hash :kaocha.watch/tracker
                                :kaocha.plugin.affected/decision :kaocha.plugin.affected/snapshot))))
  (pre-load [config]
    (let [state (begin! config)
          reporter (:kaocha/reporter config)]
      (assoc config ::state state
             :kaocha/reporter
             (fn [event]
               (reporter event)
               (when (and (= :summary (:type event)) @(:committed? state))
                 (println)
                 (println "RUN-RECEIPT" (str (:path state))) (flush))))))
  (post-run [r] (finish! r)))
