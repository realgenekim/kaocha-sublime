(ns kaocha-sublime.heap
  (:require [clojure.string :as str])
  (:import (java.lang.management ManagementFactory)))

(defn- size [s]
  (when-let [[_ n unit] (re-matches #"(?i)([0-9]+)([kmg]?)" s)]
    (* (bigint n) (get {"" 1 "k" 1024 "m" 1048576 "g" 1073741824} (str/lower-case unit)))))

;; INTENT: AGENT-HEAP-011
(defn admission [args effective ceiling]
  (let [xmx (last (filter #(str/starts-with? % "-Xmx") args))
        xms (last (filter #(str/starts-with? % "-Xms") args))
        requested (some-> xmx (subs 4) size)]
    {:requested-xms xms :requested-xmx xmx :requested-bytes requested
     :effective-bytes effective :ceiling-bytes ceiling
     :admitted? (boolean (and requested (pos? requested) (pos? effective) (<= effective requested ceiling)))
     :reason (if (and requested (pos? requested) (pos? effective) (<= effective requested ceiling)) :none :heap-cap)}))

(defn current [ceiling]
  (admission (.getInputArguments (ManagementFactory/getRuntimeMXBean))
             (.maxMemory (Runtime/getRuntime)) ceiling))
