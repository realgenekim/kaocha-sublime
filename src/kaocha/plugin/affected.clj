(ns kaocha.plugin.affected
  "Conservative namespace selection using Kaocha's tools.namespace graph."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.result :as result]
            [kaocha.testable :as testable]
            [kaocha.plugin.run-receipt :as receipt]
            [lambdaisland.tools.namespace.dependency :as dep]
            [lambdaisland.tools.namespace.dir :as dir]
            [lambdaisland.tools.namespace.file :as file]
            [lambdaisland.tools.namespace.track :as track])
  (:import (java.nio.file Files)))

(defn closure [graph known changed]
  (when (or (nil? graph) (not (set/subset? changed known)))
    (throw (ex-info "Changed namespace absent from dependency tracker" {:changed changed})))
  (set/union changed (dep/transitive-dependents-set graph changed)))

(defn- canonical-path [f] (.getCanonicalPath (io/file f)))

(defn- roots [config]
  (->> (:kaocha/tests config)
       (mapcat #(concat (:kaocha/source-paths %) (:kaocha/test-paths %)))
       (concat ["resources"] (::paths config))
       (map io/file) (filter #(.exists %)) distinct))

(defn- snapshot [config]
  (into {}
        (for [f (distinct (concat (mapcat file-seq (roots config))
                                  (map io/file ["deps.edn" "tests.edn"])))
              :when (.isFile ^java.io.File f)]
          [(canonical-path f) (receipt/sha256 (Files/readAllBytes (.toPath ^java.io.File f)))])))

(defn- git-files [since]
  (let [sha (shell/sh "git" "rev-parse" "--verify" (str since "^{commit}"))]
    (when-not (zero? (:exit sha)) (throw (ex-info "Invalid affected-since commit" {:since since})))
    (let [diff (shell/sh "git" "diff" "--name-only" "-z" (str/trim (:out sha)) "--")
          new (shell/sh "git" "ls-files" "--others" "--exclude-standard" "-z")]
      (when-not (every? #(zero? (:exit %)) [diff new])
        (throw (ex-info "Cannot enumerate changed files" {})))
      (set (map canonical-path (remove str/blank? (str/split (str (:out diff) (:out new)) #"\u0000")))))))

(defn- changed-files [before after]
  (set (filter #(not= (get before %) (get after %))
               (set/union (set (keys before)) (set (keys after))))))

(defn- decision [config]
  (let [since (or (get-in config [:kaocha/cli-options :affected-since]) (::since config))
        watch-tracker (:kaocha.watch/tracker config)
        enabled? (or since watch-tracker)]
    (when enabled?
      (try
        (let [now (snapshot config)
              previous (::snapshot watch-tracker)
              changed (cond
                        (and since (not (contains? #{":tracker" :tracker} since))) (git-files since)
                        (and watch-tracker previous) (changed-files previous now)
                        :else nil)
              tracker (or watch-tracker (dir/scan-dirs (track/tracker) (roots config)))
              filemap (into {} (map (fn [[f n]] [(canonical-path f) n])) (::file/filemap tracker))
              graph (::track/deps tracker)
              known (set (vals filemap))
              followup (when (and (some? changed) (empty? changed))
                         (::followup watch-tracker))
              bad (first (remove #(and (str/ends-with? % ".clj") (contains? filemap %)
                                      (.isFile (io/file %))) changed))
              reason (cond
                       (nil? changed) "changed set unknown (initial run or no tracker snapshot)"
                       (and (empty? changed) (not followup)) "no changed files known (manual run)"
                       (:kaocha.watch/tracker-error tracker) "tracker error"
                       (seq (::file/load-error tracker)) "namespace scan error"
                       bad (str "non-namespace, missing, or unsupported changed file: " bad)
                       (nil? graph) "dependency graph unavailable")]
          (merge {:snapshot now :files (vec (sort changed)) :known known}
                 (if reason {:reason reason}
                     {:closure (if followup (:closure followup)
                                   (closure graph known (set (map filemap changed))))})))
        (catch Exception e {:reason (str "selection unavailable: " (.getMessage e)) :files []})))))

(defn- namespace-nodes [plan]
  (filter #(= :kaocha.type/ns (:kaocha.testable/type %)) (testable/test-seq plan)))

(defn- apply-selection [plan]
  (if-let [d (::decision plan)]
    (let [nodes (namespace-nodes plan)
          names (set (map :kaocha.ns/name nodes))
          unknown (set/difference names (:known d))
          reason (or (:reason d)
                     (when (:kaocha.watch/error? plan) "watch reload failed")
                     (when (seq unknown) (str "test namespaces absent from graph: " (pr-str unknown)))
                     (when (some #(not (contains? #{:kaocha.type/clojure.test :kaocha.type/ns :kaocha.type/var}
                                                  (:kaocha.testable/type %)))
                                 (rest (testable/test-seq plan)))
                       "unsupported testable type"))
          chosen (if reason names (set/intersection names (:closure d)))
          selection {:files (:files d) :namespaces (vec (sort chosen))
                     :closure (vec (sort (:closure d)))
                     :total (count names) :selected (count chosen) :reason reason}
          narrow (fn narrow [t]
                   (cond
                     (and (= :kaocha.type/ns (:kaocha.testable/type t))
                          (not (contains? chosen (:kaocha.ns/name t))))
                     (assoc t :kaocha.testable/skip true)
                     (:kaocha.test-plan/tests t) (update t :kaocha.test-plan/tests #(mapv narrow %))
                     :else t))]
      (when reason (println "AFFECTED FULL:" reason))
      (println (format "AFFECTED %d/%d namespaces from %d changed files"
                       (count chosen) (count names) (count (:files d))))
      (cond-> (assoc plan ::selection selection)
        (not reason) narrow))
    plan))

(defplugin kaocha.plugin/affected
  (cli-options [options]
    (conj options [nil "--affected-since SHA|:tracker" "Run namespaces affected since a commit or tracker snapshot."]))
  (config [config]
    (let [d (decision config)]
      (cond-> (assoc config ::decision d)
        ;; Kaocha's watch focus is transient. A resource/config fallback must not
        ;; quietly run only yesterday's failing test. Preserve explicit CLI focus.
        (and d (:kaocha.watch/focus config))
        (assoc :kaocha.filter/focus (or (get-in config [:kaocha/cli-options :focus])
                                      (get-in config [:kaocha.watch/tracker ::base-focus]))))))
  (pre-load [config]
    (cond-> config
      (and (:kaocha.watch/tracker config) (get-in config [::decision :snapshot]))
      (assoc-in [:kaocha.watch/tracker ::snapshot] (get-in config [::decision :snapshot]))
      (and (:kaocha.watch/tracker config)
           (not (contains? (:kaocha.watch/tracker config) ::base-focus)))
      (assoc-in [:kaocha.watch/tracker ::base-focus] (:kaocha.filter/focus config))))
  (pre-run [plan] (apply-selection plan))
  (post-run [r]
    (cond-> r
      (:kaocha.watch/tracker r)
      (assoc-in [:kaocha.watch/tracker ::followup]
                (when (and (seq (:kaocha.watch/focus r))
                           (not (result/failed? r))
                           (nil? (get-in r [::selection :reason])))
                  (select-keys (::decision r) [:closure]))))))
