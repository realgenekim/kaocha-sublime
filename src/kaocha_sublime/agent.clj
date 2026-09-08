(ns kaocha-sublime.agent
  "Runner-owned event stream. Raw test output never enters this destination."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.report]
            [kaocha-sublime.candidate :as candidate]
            [kaocha-sublime.protocol :as protocol])
  (:import (java.nio.file Files)))

(def ^:dynamic *stream* nil)

(defn new-stream! [{:keys [dir run-id] :as state}]
  (let [events (io/file dir (str run-id ".events"))]
    (io/make-parents events)
    (Files/createFile (.toPath events) (make-array java.nio.file.attribute.FileAttribute 0))
    (assoc state :events (str events)
                 :budget (atom {:shown 0 :omitted 0 :bytes 0 :seen 0})
                 :failures (atom []) :refused? (atom false))))

(defn common [state]
  {:v 1 :id (:run-id state) :mode (:mode state) :candidate (:candidate state)})

(defn emit! [state kind record]
  (let [line (protocol/record-line kind record)]
    (locking state (spit (:events state) line :append true))
    line))

(defn refuse! [state reason]
  (when (compare-and-set! (:refused? state) false true)
    (let [r (protocol/status :refused reason {:id (:run-id state) :active (some-> (:tmp state) str)})]
      (candidate/atomic-edn! (io/file (:dir state) "REFUSED")
                            {:run-id (:run-id state) :session (:session state)
                             :candidate (:candidate state) :record r})
      (emit! state :status r))))

(defn start! [state]
  (let [files (get-in state [:trigger :files])
        shown (loop [xs files out []]
                (if-let [p (first xs)]
                  (if (and (< (count out) 4) (<= (count (protocol/json (conj out p))) 512))
                    (recur (next xs) (conj out p)) out) out))]
    (try
      (emit! state :start (merge (common state)
                                {:session (:session state) :trigger shown
                                 :trigger_omitted (- (count files) (count shown))
                                 :closure nil :active (str (:tmp state))}))
      (catch clojure.lang.ExceptionInfo _ (refuse! state :protocol-error)))))

(defn- qualified-test [e]
  (let [id (get-in e [:kaocha/testable :kaocha.testable/id])
        v (first t/*testing-vars*)]
    (cond (and (keyword? id) (namespace id)) (str (namespace id) "/" (name id))
          v (str (ns-name (:ns (meta v))) "/" (:name (meta v)))
          :else nil)))

(defn- equality-values [e]
  ;; clojure.test records (not (= evaluated-expected evaluated-actual)). No
  ;; assertion form is evaluated here, and no arbitrary seq is traversed.
  (let [a (:actual e)]
    (when (and (instance? clojure.lang.PersistentList a) (= 'not (first a)))
      (let [eq (second a)]
        (when (and (instance? clojure.lang.PersistentList eq)
                   (contains? #{'= 'clojure.core/=} (first eq)) (= 3 (count eq)))
          [(second eq) (nth eq 2)])))))

(defn- exception-evidence [x]
  (when (instance? Throwable x)
    (loop [e x depth 0 location nil]
      (let [class-name (.getName (class e))
            safe? (or (str/starts-with? class-name "java.") (= class-name "clojure.lang.ExceptionInfo")
                      (= class-name "clojure.lang.Compiler$CompilerException"))
            data (when (and safe? (instance? clojure.lang.IExceptionInfo e)) (ex-data e))
            file (or (:clojure.error/source data) (:source data) (:file data))
            line (or (:clojure.error/line data) (:line data))
            location (or location (when (and (string? file) (integer? line) (pos? line)) {:file file :line line}))
            cause (when (and safe? (< depth 6)) (.getCause ^Throwable e))]
        (if (and cause (not (identical? cause e))) (recur cause (inc depth) location)
          (merge location {:text (str class-name ": " (if safe? (.getMessage ^Throwable e) "<message unavailable>"))}))))))

(defn- source-path [state file meta-file]
  (when (string? file)
    (let [paths (concat [file]
                        (map #(str (io/file % file)) (:roots state))
                        (when (and (string? meta-file) (= (.getName (io/file file)) (.getName (io/file meta-file))))
                          (concat [meta-file] (map #(str (io/file % meta-file)) (:roots state)))))
          found (first (filter #(.isFile (io/file %)) paths))
          resource (when-not found (io/resource (or meta-file file)))
          found (or found (when (and resource (= "file" (.getProtocol resource))) (str (io/file (.toURI resource)))))
          path (if found (candidate/canonical found) file)]
      (if (and found (:workspace state) (candidate/within? (:workspace state) path))
        (str (.relativize (.toPath (io/file (:workspace state))) (.toPath (io/file path)))) path))))

(defn failure-record [state e event]
  (let [testable (:kaocha/testable e) m (:kaocha.testable/meta testable)
        load? (some? (:kaocha.testable/load-error testable))
        eq (equality-values e)
        exception (exception-evidence (:actual e))
        context (or (last (:testing-contexts e)) (first t/*testing-contexts*) (:message e))
        fields {:context (when (some? context) ((if (string? context) protocol/text-preview protocol/preview) context 160))
                :expr (when (contains? e :expected) (protocol/preview (:expected e) 160))
                :expected (when (or eq (contains? e :expected))
                            (protocol/preview (if eq (first eq) (:expected e)) 256))
                :actual (when (contains? e :actual)
                          (if exception (protocol/text-preview (:text exception) 256)
                            (protocol/preview (if eq (second eq) (:actual e)) 256)))
                :diff (when eq (protocol/difference-preview (first eq) (second eq)))}
        file (or (:file exception) (:file e) (:file m) (:kaocha.testable/load-error-file testable))
        file (source-path state file (:file m))
        line (or (:line exception) (:line e) (:line m) (:kaocha.testable/load-error-line testable))]
    (merge (common state)
           {:event event :kind (cond load? :load (isa? hierarchy/hierarchy (:type e) :error) :error :else :fail)
            :test (when-not load? (qualified-test e)) :file file
            :line (when (and (integer? line) (pos? line)) line)
            :truncated (vec (keep (fn [[k p]] (when (:truncated? p) k)) fields))
            :unavailable (vec (keep (fn [[k p]] (when (or (nil? p) (:unavailable? p)) k)) fields))}
           (into {} (map (fn [[k p]] [k (:text p)])) fields))))

;; INTENT: AGENT-BUDGET-013
(defn failure! [state e]
  (locking state
    (let [event (:seen (swap! (:budget state) update :seen inc))
          record (failure-record state e event)]
      (swap! (:failures state) conj record)
      (if (or @(:refused? state) (>= (:shown @(:budget state)) 20))
        (swap! (:budget state) update :omitted inc)
        (try
          (let [line (protocol/record-line :fail record)]
            (if (> (+ (:bytes @(:budget state)) (count line)) 16384)
              (swap! (:budget state) update :omitted inc)
              (do (spit (:events state) line :append true)
                  (swap! (:budget state) #(-> % (update :shown inc) (update :bytes + (count line)))))))
          (catch clojure.lang.ExceptionInfo _
            (swap! (:budget state) update :omitted inc)
            (refuse! state :protocol-error)))))))

(defn reporter [event]
  (when (and *stream* (hierarchy/fail-type? event)) (failure! *stream* event)))

;; Kaocha resolves reporter Vars after loading plugins. Add this one projection
;; to its existing namespace without replacing or shadowing its human reporters.
(when (= #'clojure.core/agent (ns-resolve 'kaocha.report 'agent))
  (ns-unmap 'kaocha.report 'agent))
(intern 'kaocha.report 'agent reporter)

;; INTENT: AGENT-PUBLISH-010
(defn done! [state receipt]
  (when-not @(:refused? state)
    (try (emit! state :done (protocol/done-record receipt (:path state) (candidate/file-hash (:path state))))
         (catch clojure.lang.ExceptionInfo _ (refuse! state :protocol-error)))))
