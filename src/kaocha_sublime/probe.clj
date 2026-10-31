(ns kaocha-sublime.probe
  "The warm adapter entrance: reserve, reload named namespaces, run, release."
  (:require [clojure.string :as str]
            [kaocha.api :as api]
            [kaocha.classpath :as classpath]
            [kaocha.repl :as repl]
            [kaocha.plugin.run-receipt :as receipt]
            [kaocha-sublime.candidate :as candidate]
            [kaocha-sublime.protocol :as protocol]))

;; INTENT: LOOP-PERF-MODE-008
(defn unskip-meta
  "`cfg` with the metadata keys in `allowed` removed from every :kaocha.filter/skip-meta,
  at the top level and on each suite, so a run focused on ^:perf vars can run them
  although tests.edn skips them in the commit gate."
  [cfg allowed]
  (let [allowed (set allowed)
        strip (fn [m] (cond-> m
                        (contains? m :kaocha.filter/skip-meta)
                        (update :kaocha.filter/skip-meta #(vec (remove allowed %)))))]
    (if (seq allowed)
      (-> (strip cfg) (update :kaocha/tests #(mapv strip %)))
      cfg)))

;; INTENT: AGENT-PROBE-RELOAD-017
(defn run
  "Probe a vector of namespace or namespace/Var symbols, in dependency order.
  Options are Kaocha config overrides, plus :suite (default :unit).
  ::allow-meta (a collection of metadata keywords) un-skips tests that tests.edn's
  :skip-meta would skip. Returns :events, :path, :exit, and :result or :error. Relay
  only :events; :exit describes this run, not token-bound image acceptance."
  ([targets] (run targets {}))
  ([targets {:keys [suite] :or {suite :unit} :as options}]
   (let [observed (atom nil)]
     (binding [receipt/*run-observer* observed]
       (try
         (when-not (and (vector? targets) (seq targets) (every? symbol? targets))
           (throw (ex-info "Probe targets must be a nonempty vector of symbols" {})))
         (let [namespaces (mapv #(if-let [n (namespace %)] (symbol n) %) targets)
               tests (filterv #(or (namespace %) (str/ends-with? (name %) "-test")) targets)
               cfg (-> (repl/config (assoc (dissoc options :suite ::allow-meta) ::receipt/agent? true))
                       (unskip-meta (::allow-meta options)))
               others (remove #{suite} (map :kaocha.testable/id (:kaocha/tests cfg)))
               cfg (-> cfg
                       (assoc ::receipt/probe-namespaces namespaces
                              ;; INTENT: AGENT-REQUESTED-SCOPE-020
                              ::receipt/probe-targets targets
                              ::receipt/configured-skip (:kaocha.filter/skip cfg)
                              ::receipt/probe-skipped-suites (vec others)
                              :kaocha.filter/focus (mapv keyword (if (seq tests) tests targets)))
                       (update :kaocha.filter/skip into others))]
           (doseq [path (distinct (mapcat #(concat (:kaocha/source-paths %) (:kaocha/test-paths %))
                                         (:kaocha/tests cfg)))]
             (classpath/add-classpath path))
           (let [result (api/run cfg) path (::receipt/path result)
                 r (candidate/read-edn path)
                 outcome (protocol/outcome (:totals r) (:coverage r))]
             {:events (:events @observed) :path path :result result
              :exit (case outcome :pass 0 (:fail :error) 1 2)}))
         (catch Throwable t
           {:events (:events @observed) :path nil :error t :exit 2}))))))
