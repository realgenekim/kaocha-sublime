(ns kaocha-sublime.image
  "Attest the existing Kaocha watch reload, without another filesystem watcher."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kaocha-sublime.candidate :as candidate]
            [lambdaisland.tools.namespace.file :as file]
            [lambdaisland.tools.namespace.reload :as reload]
            [lambdaisland.tools.namespace.repl :as repl])
  (:import (java.util UUID)))

(defonce session-id (str (UUID/randomUUID)))
(defonce observed (atom 0))
(defonce images (atom {}))
(defonce active (atom nil))
(defonce run-lock (java.util.concurrent.locks.ReentrantLock.))
(defonce cold-launch? (atom false))

(defn invalidate!
  "Adapters call this after untracked REPL evaluation that may alter project Vars.
  A probe then stays unknown; the next watch run repairs through a full reload."
  []
  (swap! observed inc)
  (swap! images #(into {} (map (fn [[dir state]] [dir (assoc state :trusted? false)])) %))
  nil)

;; INTENT: AGENT-PROBE-RELOAD-017
(defn reload-probe!
  "Unload and reload explicit namespaces using the watcher's reload machinery.
  Callers supply dependencies before their dependents; this is not graph repair
  or loaded-image attestation. Runs inside the probe's reserved load phase."
  [namespaces]
  (invalidate!)
  (let [namespaces (vec (distinct namespaces))
        tracker {:lambdaisland.tools.namespace.track/unload (reverse namespaces)
                 :lambdaisland.tools.namespace.track/load namespaces}
        enabled (repl/remove-disabled tracker)]
    (when-not (= tracker enabled)
      (throw (ex-info "Probe cannot reload a namespace with reload disabled" {:reason :identity-unproved})))
    (let [result (reload/track-reload tracker)]
      (when-let [error (:lambdaisland.tools.namespace.reload/error result)]
        (throw (ex-info "Probe namespace reload failed"
                        {:namespace (:lambdaisland.tools.namespace.reload/error-ns result)} error))))))

(defn mode [config]
  (let [stack (map #(.getClassName ^StackTraceElement %) (.getStackTrace (Thread/currentThread)))]
    (cond (:kaocha.watch/tracker config) :watch
          (some #(str/starts-with? % "kaocha.repl$") stack) :probe
          (and (some #(str/starts-with? % "kaocha.runner$") stack)
               (boolean (re-find #"(?:^| )-m kaocha\.runner(?: |$)" (System/getProperty "sun.java.command" "")))) :gate
          :else :probe)))

(defn session [config config-hash]
  (let [workspace (candidate/canonical ".")
        roots (->> (:kaocha/tests config)
                   (mapcat #(concat (:kaocha/source-paths %) (:kaocha/test-paths %)))
                   (map candidate/canonical) distinct sort vec)
        classpath (System/getProperty "java.class.path")
        cp-paths (mapv candidate/canonical (str/split classpath (re-pattern java.io.File/pathSeparator)))
        external-roots (filter #(and (.isDirectory (io/file %))
                                    (not-any? (fn [root] (candidate/within? root %)) roots)) cp-paths)
        snapshot-roots (vec (sort (distinct (concat roots external-roots [(candidate/canonical "resources")]
                                        (map candidate/canonical (:kaocha.plugin.affected/paths config))))))
        config-files (mapv candidate/canonical
                          (distinct ["deps.edn" "tests.edn"
                                     (or (get-in config [:kaocha/cli-options :config-file]) "tests.edn")]))
        config-content (mapv (fn [p] [p (candidate/file-hash p)]) config-files)
        cp-stamps (candidate/classpath-stamps cp-paths)
        external-content (candidate/snapshot {:roots external-roots})
        generation (candidate/data-hash [config-hash config-content cp-stamps external-content])]
    (when (= :gate (mode config)) (reset! cold-launch? true))
    {:workspace workspace :session session-id :generation generation :roots roots
     :snapshot-roots snapshot-roots :config-files config-files
     :classpath-stamps cp-stamps
     :classpath-generation (candidate/data-hash [cp-stamps external-content])}))

(defn capture [session]
  {:manifest (candidate/snapshot session) :observed @observed
   :file-stamps (candidate/change-stamps session)
   :classpath-stable? (or (nil? (:classpath-stamps session))
                          (= (:classpath-stamps session)
                             (candidate/classpath-stamps (keys (:classpath-stamps session)))))})

(defn- code-file? [p] (or (str/ends-with? p ".clj") (str/ends-with? p ".cljc")))

;; INTENT: AGENT-IMAGE-012
(defn reload! [f tracker]
  (if-let [state @active]
    (let [previous (get @images (:dir state))
          before (capture state)
          delta (candidate/changed (:manifest previous) (:manifest before))
          ;; Feed hash-detected saves into Kaocha's own tracker. This repairs its
          ;; mtime blind spot without treating a disk comparison as reload proof.
          repair? (and previous (not (:trusted? previous))
                       (= (:generation previous) (:generation state)) @cold-launch?)
          code-delta (filter #(and (code-file? %) (some (fn [root] (candidate/within? root %)) (:roots state)))
                             (if repair?
                                         (distinct (concat (keys (:manifest before)) (keys (:manifest previous))))
                                         delta))
          existing (map io/file (filter #(get (:manifest before) %) code-delta))
          deleted (map io/file (remove #(get (:manifest before) %) code-delta))
          prior-filemap (:lambdaisland.tools.namespace.file/filemap tracker)
          deleted-names (set (keep #(or (get prior-filemap %) (get (:filemap previous) %)) deleted))
          tracker (if previous
                    (-> tracker
                        (file/remove-files deleted)
                        (file/add-files existing)
                        ;; tools.namespace's graph omits isolated namespaces:
                        ;; deletion must still remove their loaded Vars.
                        (update :lambdaisland.tools.namespace.track/unload
                                #(distinct (concat % (sort deleted-names))))
                        (update :lambdaisland.tools.namespace.track/load
                                #(remove deleted-names %))) tracker)
          tracker (repl/remove-disabled tracker)
          filemap (merge (:filemap previous) prior-filemap (:lambdaisland.tools.namespace.file/filemap tracker))
          scheduled (set (concat (:lambdaisland.tools.namespace.track/load tracker)
                                 (:lambdaisland.tools.namespace.track/unload tracker)))
          changed-covered? (every? (fn [p]
                                    (and (code-file? p)
                                         (some #(candidate/within? % p) (:roots state))
                                         (let [n (get filemap (io/file p))]
                                           (and n (or (contains? scheduled n)
                                                      (and (nil? (get (:manifest before) p)) (nil? (find-ns n)))))))) delta)
          repaired? (and repair?
                         (every? #(contains? scheduled (get filemap (io/file %))) code-delta))
          valid-base? (if previous
                        (and (or (:trusted? previous) repaired?) (= (:generation previous) (:generation state))
                             changed-covered?)
                        (and @cold-launch? (empty? @images)))
          result (f tracker)
          after (capture state)
          exact? (and valid-base? (some? (:file-stamps before)) (some? (:file-stamps after))
                      (:classpath-stable? before) (:classpath-stable? after)
                      (candidate/stable-image? before after)
                      (= (:manifest state) (:manifest before))
                      (= (:observed state) (:observed before)))]
      (reset! (:attestation state) {:exact? (boolean exact?) :loaded after
                                   :base-valid? (boolean valid-base?) :changed-covered? changed-covered?
                                   :reload-stable? (candidate/stable-image? before after)
                                   :reload-observed [(:observed before) (:observed after)]
                                   :scheduled (vec (sort scheduled)) :changed (vec (sort delta))
                                   :filemap filemap
                                   :load-error? (boolean (or (:kaocha.watch/tracker-error result)
                                                            (:lambdaisland.tools.namespace.reload/error result)))})
      result)
    (f tracker)))

(defn admission-identity [state]
  (let [previous (get @images (:dir state))]
    (cond
      (= :watch (:mode state)) false
      (nil? previous) (and (= :gate (:mode state)) (empty? @images))
      :else (and (:trusted? previous) (= (:generation previous) (:generation state))
                 (= (:manifest previous) (:manifest state))
                 (= (:observed previous) (:observed state))))))

(defn finish-identity! [state]
  (let [after (capture state)
        exact? (and (some? (:file-stamps state)) (some? (:file-stamps after))
                    (:classpath-stable? after) @(:configuration-valid? state) (:exact? @(:attestation state))
                    (candidate/stable-image? (select-keys state [:manifest :observed :file-stamps]) after))]
    (swap! (:attestation state) assoc :finish-observed (:observed after)
           :file-stamps-stable? (and (some? (:file-stamps state))
                                    (= (:file-stamps state) (:file-stamps after)))
           :classpath-stable? (:classpath-stable? after)
           :disk-stable? (= (:manifest state) (:manifest after))
           :configuration-valid? @(:configuration-valid? state))
    (swap! images assoc (:dir state)
           (merge after {:generation (:generation state)
                         :filemap (:filemap @(:attestation state))
                         :trusted? (boolean (and exact? (not (:load-error? @(:attestation state)))))}))
    (reset! active nil)
    (if exact? :exact :unknown)))
