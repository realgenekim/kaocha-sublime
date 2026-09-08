(ns kaocha-sublime.reap
  "Explicit cleanup of dead local JVM receipts. Never signals any process."
  (:require [clojure.java.io :as io]
            [kaocha-sublime.status :as status])
  (:import (java.lang ProcessHandle)
           (java.nio.file Files StandardCopyOption)
           (java.util UUID)))

(defn- dead? [{:keys [jvm-pid process-start]}]
  (when (integer? jvm-pid)
    (let [handle (ProcessHandle/of jvm-pid)]
      (or (not (.isPresent handle))
          (not (.isAlive (.get handle)))
          (and process-start
               (let [actual (.startInstant (.info (.get handle)))]
                 (and (.isPresent actual) (not= process-start (str (.get actual))))))))))

;; INTENT: STATUS-CRASH-004
(defn reap! [dir]
  (reduce
    (fn [n file]
      (let [r (status/read-edn file)]
        (if (and (:started r) (dead? r))
          (let [marker (io/file dir "INTERRUPTED")
                previous (status/read-edn marker)]
            ;; Publish the tombstone BEFORE removing RUNNING. A crash here can
            ;; leave both, but cannot expose an older green as the current run.
            (when (or (nil? previous) (status/newer? (:started r) (:started previous)))
              (let [tmp (io/file dir (str "INTERRUPTED." (UUID/randomUUID) ".tmp"))]
                (spit tmp (pr-str (select-keys r [:run-id :started :jvm-pid :process-start])))
                (Files/move (.toPath tmp) (.toPath marker)
                            (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                            StandardCopyOption/REPLACE_EXISTING]))))
            (Files/deleteIfExists (.toPath file))
            (inc n))
          n)))
    0 (status/running-files dir)))

(defn -main [& args]
  (let [{:keys [dir]} (status/options args)]
    (println (str "KAOCHA-REAP reaped=" (reap! dir) " dir=" (status/token dir)))))
