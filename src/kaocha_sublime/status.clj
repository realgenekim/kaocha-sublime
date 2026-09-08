(ns kaocha-sublime.status
  "One-shot, dependency-free receipt reader (JVM Clojure or Babashka)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaocha-sublime.candidate :as candidate]
            [kaocha-sublime.protocol :as protocol])
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
  (loop [args args opts {:dir "target/kaocha-runs/" :timeout 120.0}]
    (if (empty? args)
      (do (when (or (and (:legacy opts) (or (:follow opts) (:token opts) (:mark opts)))
                    (and (:mark opts) (or (:follow opts) (:token opts) (:since opts)))
                    (and (:token opts) (or (:since opts) (:ns opts)))
                    (and (:follow opts) (not (:token opts))))
            (throw (ex-info "Conflicting status operations" {:reason :protocol-error})))
          opts)
      (let [[flag & more] args]
        (cond
          (contains? #{"--follow" "--legacy"} flag)
          (recur more (assoc opts (keyword (subs flag 2)) true))
          (= flag "--mark")
          (let [[paths tail] (split-with #(not (str/starts-with? % "--")) more)]
            (when (empty? paths) (throw (ex-info "--mark requires paths" {})))
            (recur tail (assoc opts :mark (vec paths))))
          (contains? #{"--dir" "--since" "--ns" "--token" "--timeout"} flag)
          (let [[value & tail] more]
            (when (or (nil? value) (str/starts-with? value "--"))
              (throw (ex-info "Missing argument" {})))
            (recur tail (assoc opts (keyword (subs flag 2))
                               (if (= flag "--timeout") (Double/parseDouble value) value))))
          :else (throw (ex-info "Unknown option" {})))))))

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

(defn- response [state reason token & [fields]]
  {:kind :status :record (protocol/status state reason (merge {:token token} fields)) :exit 2})

(defn- exact? [token r]
  (and (= (:workspace token) (:workspace r)) (= (:session token) (:session r))
       (= (:generation token) (:generation r)) (= (:candidate token) (:candidate r))))

(defn- token-covers? [token r]
  (let [{:keys [kind namespaces]} (:required-scope token)]
    (case kind
      :full (and (= :complete (:coverage r))
                 (some? (:closure r))
                 (= (get-in r [:closure :selected]) (get-in r [:closure :total])))
      :namespaces (and (seq namespaces)
                       (set/subset? (set namespaces) (set (:executed-namespaces r)))
                       (not-any? (set namespaces) (:partial-namespaces r)))
      false)))

(defn- pointed [dir]
  (let [p (read-edn (io/file dir "LATEST"))
        path (:path p) r (when (string? path) (read-edn path))]
    (when (and r (= (:run-id p) (:run-id r)) (= (:finished p) (:finished r))
               (= (candidate/canonical dir) (some-> path io/file .getParent candidate/canonical))
               (= (:sha256 p) (candidate/file-hash path))
               (= 1 (:schema r))
               (= (:candidate r) (candidate/candidate-id r)))
      (assoc r ::path path))))

(defn- primary [r]
  (if (and (:auto-followup? r) (empty? (get-in r [:trigger :files])) (:followup-of r))
    (let [p (read-edn (:followup-of r))]
      (if (and p (= 1 (:schema p)) (= (:candidate p) (candidate/candidate-id p))
               (= (:parent r) (:run-id p))
               (= (:parent-sha256 r) (candidate/file-hash (:followup-of r)))
               (exact? r p))
        (assoc p ::path (:followup-of r)) r)) r))

(defn- terminal [token r]
  (let [covered? (token-covers? token r)
        record (protocol/done-record r (::path r) (candidate/file-hash (::path r)))
        identity (if (and (exact? token r) (= :exact (:identity r))) :exact :unknown)
        exit (protocol/exit-code {:identity identity
                                  :coverage (if covered? :complete :partial)
                                  :outcome (:outcome record)})]
    (cond
      (not (protocol/valid-record? :done record))
      (response :refused :protocol-error (:token token))
      (not= identity :exact) (response :unavailable :identity-unproved (:token token) {:id (:run-id r) :receipt (::path r)})
      (and (not covered?) (not (contains? #{:fail :error} (:outcome record))))
      (response :uncovered :scope-missing (:token token) {:id (:run-id r) :identity :exact :receipt (::path r)})
      :else {:kind :done :record record :exit exit})))

(defn observe
  "One token observation. Follows LATEST and its authenticated primary link;
  never selects a final receipt by filename, mtime, or test stdout."
  [{:keys [dir token workspace]}]
  (let [_ (when-not (and (string? token) (re-matches #"[a-f0-9]{64}" token))
            (throw (ex-info "Invalid token" {:reason :protocol-error})))
        t (some-> (read-edn (io/file dir "tokens" (str token ".edn"))) (assoc :token token))
        session (read-edn (io/file dir "SESSION"))
        tmps (vec (running-files dir))
        r (some-> (pointed dir) primary)
        refused (read-edn (io/file dir "REFUSED"))]
    (cond
      (nil? t) (response :unavailable :no-receipt token)
      (or (not= 1 (:schema t))
          (not= (:candidate t) (candidate/candidate-id t))
          (not= token (candidate/data-hash [(:candidate t) (:required-scope t) (:required-paths t)])))
      (response :refused :protocol-error token)
      (or (not= (:workspace t) (candidate/canonical (or workspace ".")))
          (not= (:workspace t) (:workspace session)))
      (response :refused :protocol-error token)
      (:refused session) (response :refused (:refused session) token)
      (or (not= (:session t) (:session session)) (not= (:generation t) (:generation session))
          (and (:classpath-stamps t)
               (not= (:classpath-stamps t) (candidate/classpath-stamps (keys (:classpath-stamps t))))))
      (response :unavailable :identity-unproved token)
      (some (fn [p] (not-any? #(candidate/within? % p) (:roots t))) (keys (:required-paths t)))
      (response :uncovered :scope-missing token)
      (or (not= (:manifest t) (candidate/snapshot session))
          (some (fn [[p hash]] (not= hash (candidate/file-hash p))) (:required-paths t)))
      (response :superseded :candidate-changed token)
      (and (= (:candidate t) (:candidate refused)) (= (:session t) (:session refused)))
      {:kind :status :record (assoc (:record refused) :token token) :exit 2}
      (> (count tmps) 1) (response :refused :protocol-error token)
      (seq tmps)
      (let [path (candidate/canonical (first tmps)) active (read-edn path)
            elapsed (when (:started active)
                      (max 0 (- (System/currentTimeMillis) (.toEpochMilli (Instant/parse (:started active))))))
            fields {:id (:run-id active) :active path :elapsed_ms elapsed
                    :phase (or (:phase active) :load)
                    :identity (if (and (exact? t active) (= :exact (:identity active))) :exact :unknown)}]
        (if (exact? t active)
          (case (candidate/process-state active)
            :dead (response :killed :process-dead token fields)
            :unknown (response :unavailable :identity-unproved token fields)
            :alive (response (if (and elapsed (> elapsed 30000)) :late :running)
                             (if (and elapsed (> elapsed 30000)) :over-p95 :none) token fields))
          (response :waiting :save-unobserved token {:phase :queued})))
      :else
      (let [dead (read-edn (io/file dir "INTERRUPTED"))]
        (cond
          (and (exact? t dead) (or (nil? r) (newer? (:started dead) (:started r))))
          (response :killed :process-dead token {:id (:run-id dead) :identity :exact})
          (and r (exact? t r)) (terminal t r)
          (and (.exists (io/file dir "LATEST")) (nil? r)) (response :refused :protocol-error token)
          :else (response :waiting :save-unobserved token {:phase :queued}))))))

(defn follow-result [{:keys [follow timeout] :as opts}]
  (when (or (neg? timeout) (Double/isNaN timeout) (Double/isInfinite timeout))
    (throw (ex-info "Invalid timeout" {:reason :protocol-error})))
  (let [start (System/nanoTime) deadline (+ start (* timeout 1e9))]
    (loop []
      (let [r (observe opts) state (get-in r [:record :state])]
        (if (and follow (contains? #{:waiting :running :late} state))
          (if (>= (System/nanoTime) deadline)
            (response :timeout :deadline (:token opts)
                      (assoc (select-keys (:record r) [:id :identity :phase :active :receipt])
                             :elapsed_ms (long (/ (- (System/nanoTime) start) 1e6))))
            (do (Thread/sleep 50) (recur))) r)))))

(defn -main [& args]
  (let [r (try
            (let [{:keys [dir mark token legacy since ns] :as opts} (options args)]
              (cond
                legacy {:legacy (status-line opts) :exit 0}
                mark (let [token (candidate/mark! dir mark
                                      (if ns {:kind :namespaces :namespaces (vec (sort (str/split ns #",")))}
                                          {:kind :full}))]
                       {:kind :status :record (protocol/status :marked :none {:token token :phase :queued}) :exit 0})
                token (follow-result opts)
                since (response :unavailable :identity-unproved nil)
                :else (if-let [r (read-edn (io/file dir "REFUSED"))]
                        {:kind :status :record (:record r) :exit 2}
                        (response :unavailable :identity-unproved nil))))
            (catch Exception e (response :refused (or (:reason (ex-data e)) :protocol-error) nil)))]
    (try
      (if (:legacy r) (println (:legacy r)) (print (protocol/record-line (:kind r) (:record r))))
      (flush)
      (System/exit (:exit r))
      (catch Exception _
        (print (protocol/record-line :status (protocol/status :refused :protocol-error)))
        (flush) (System/exit 2)))))
