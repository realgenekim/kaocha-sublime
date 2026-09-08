(ns kaocha-sublime.status
  "One-shot, dependency-free receipt reader (JVM Clojure or Babashka)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.nio.file Files)
           (java.time Instant)
           (java.net URLEncoder)))

(defn read-edn [file]
  (try (edn/read-string (slurp file)) (catch Exception _ nil)))

(defn stamp [file]
  (str (.toInstant (Files/getLastModifiedTime (.toPath (io/file file))
                                            (make-array java.nio.file.LinkOption 0)))))

(defn token [x]
  ;; Preserve a single physical line and whitespace-separated fields for odd paths.
  (str/replace (str x) #"[%\s]" #(str/replace (URLEncoder/encode % "UTF-8") "+" "%20")))

(defn options [args]
  (loop [args args opts {:dir "target/kaocha-runs/"}]
    (if (empty? args) opts
        (let [[flag value & more] args]
          (when-not (and (contains? #{"--dir" "--since" "--ns"} flag)
                         (some? value) (not (str/starts-with? value "--")))
            (throw (ex-info "Expected --dir PATH, --since FILE|UTC-STAMP, or --ns NS,..." {})))
          (recur more (assoc opts (keyword (subs flag 2)) value))))))

(defn newer? [a b] (.isAfter (Instant/parse a) (Instant/parse b)))

(defn running-files [dir]
  (filter #(str/ends-with? (.getName %) ".edn.tmp") (.listFiles (io/file dir))))

(defn- none [dir reason]
  (str "KAOCHA-STATUS NONE dir=" (token dir) (when reason (str " reason=" reason))))

(defn- save-info [since]
  (when since
    (if (.isFile (io/file since))
      {:path since :at (stamp since)}
      {:path "stamp" :at (str (Instant/parse since))})))

(defn- stale [latest save]
  (str "KAOCHA-STATUS STALE latest=" latest " newer_edit="
       (token (:path save)) "@" (:at save)))

;; INTENT: STATUS-IDENTITY-002
(defn- save-run [receipt since]
  ;; An unchanged automatic rerun is evidence about its parent save, not a new
  ;; save. Follow only the explicit link, never enumerate completed receipts.
  (if (and since (:auto-followup? receipt) (empty? (get-in receipt [:trigger :files])))
    (when-let [parent (read-edn (:followup-of receipt))]
      (when (and (:started parent) (:finished parent)
                 (not= (:run-id parent) (:run-id receipt)))
        (assoc parent ::path (:followup-of receipt))))
    receipt))

;; INTENT: STATUS-CLOSURE-005
(defn- covers [receipt requested]
  (let [closure (get-in receipt [:affected :closure])]
    (cond
      (or (str/blank? requested) (nil? closure)) "unknown"
      (set/subset? (set (str/split requested #",")) (set (map str closure))) "yes"
      :else "no")))

(defn- done [r requested]
  (let [{:keys [tests pass fail error]} (:totals r)
        {:keys [selected total]} (:affected r)]
    (str "KAOCHA-STATUS DONE id=" (token (:run-id r)) " finished=" (:finished r)
         " tests=" tests " pass=" pass " fail=" fail " error=" error
         " closure=" (or selected 0) "/" (or total 0)
         " covers=" (covers r requested) " receipt=" (token (::path r)))))

(defn- valid-receipt? [r]
  (and (string? (:run-id r)) (string? (:started r)) (string? (:finished r))
       (every? #(and (integer? %) (<= 0 %)) (map (:totals r) [:tests :pass :fail :error]))))

;; INTENT: STATUS-POINTER-001
;; INTENT: STATUS-CRASH-004
(defn status-line [{:keys [dir since ns]}]
  (let [save (save-info since)
        tmps (vec (running-files dir))
        pointer (read-edn (io/file dir "LATEST"))
        latest (when (:path pointer) (some-> (read-edn (:path pointer))
                                                            (assoc ::path (:path pointer))))
        interrupted (read-edn (io/file dir "INTERRUPTED"))]
    (cond
      (> (count tmps) 1) (none dir "multiple-running")

      (seq tmps)
      (let [tmp (first tmps) running (read-edn tmp)
            started (or (:started running) (stamp tmp))]
        (if (and save (newer? (:at save) started))
          (stale (or (:finished latest) started) save)
          (str "KAOCHA-STATUS RUNNING since=" started " tmp=" (token tmp))))

      (and interrupted (or (nil? latest) (newer? (:started interrupted) (:started latest))))
      (none dir "interrupted")

      (not (.exists (io/file dir "LATEST")))
      (none dir (when (some #(str/ends-with? (.getName %) ".edn") (.listFiles (io/file dir)))
                  "no-latest-pointer"))

      (not (and (valid-receipt? latest)
                (= (:run-id pointer) (:run-id latest))
                (= (:finished pointer) (:finished latest))))
      (none dir "invalid-latest-pointer")

      :else
      (let [r (save-run latest since)]
        (cond
          (not (valid-receipt? r)) (none dir "missing-save-receipt")
          (and save (newer? (:at save) (:started r))) (stale (:finished latest) save)
          :else (done r ns))))))

(defn -main [& args]
  (try (println (status-line (options args)))
       (catch Exception _
         ;; Invalid arguments/corrupt data never become an apparent green verdict.
         (println (none (try (:dir (options args)) (catch Exception _ "target/kaocha-runs/"))
                        "invalid-input"))
         (System/exit 2))))
