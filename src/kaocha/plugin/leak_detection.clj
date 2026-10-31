(ns kaocha.plugin.leak-detection
  "Background work a test leaves running when it returns, charged to that test.
  Design: docs/intent/leak-detection/leak-detection-design.md.

  pre-test (leaf testables only) snapshots the platform thread ids and the counters of
  Clojure's two Agent pools (future, send-off, send); post-test compares. A leak is a
  pool task submitted during the test and still active or queued at its return, or a
  platform thread born during the test and alive at its return (minus allowlisted
  names and idle pool workers). The next leaf that starts while that work is still in
  flight is marked as inheriting it; if it fails it is marked contaminated-by the
  leaker. Observation only: verdicts are unchanged unless ::fail-on-leak is set, which
  adds exactly one :fail event to the leaking var.

  Loadable without nREPL or tools.namespace, so a project can enable it in tests.edn for
  the cold gate: {:plugins [:kaocha.plugin/leak-detection]}."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.result :as result]
            [kaocha.testable :as testable])
  (:import (java.lang.management ManagementFactory ThreadInfo ThreadMXBean)
           (java.util Arrays)
           (java.util.concurrent ExecutorService ThreadPoolExecutor)))

(def ^:private ^ThreadMXBean mx (ManagementFactory/getThreadMXBean))

(def default-allow-threads
  "Thread names born inside a test that are lazily started JVM/Swing/tooling singletons."
  ["^AWT-" "^Java2D " "^TimerQueue" "^Common-Cleaner" "^process reaper" "^Attach Listener"
   "^Reference Handler" "^Finalizer" "^Signal Dispatcher" "^nREPL-" "^Image Fetcher" "^Image Animator"
   "^Keep-Alive-Timer" "^ForkJoinPool\\.commonPool-worker-"])

(def ^:private pool-name #"^clojure-agent-send(-off)?-pool-")

;; ---------------------------------------------------------------- observation

(defn- pool-counts [^ExecutorService e]
  (when (instance? ThreadPoolExecutor e)
    (let [^ThreadPoolExecutor e e
          ;; getActiveCount / getTaskCount are documented as approximate.
          active (.getActiveCount e)]
      {:active active :queued (.size (.getQueue e)) :submitted (.getTaskCount e)})))

;; INTENT: LOOP-LEAK-REPORT-009
(defn snapshot
  "What is in flight now: the sorted platform thread ids (no stacks) and the Agent pool
  counters, read from the executors installed now (an app may replace them)."
  []
  {:threads (doto (.getAllThreadIds mx) Arrays/sort)
   :pools {:send-off (pool-counts clojure.lang.Agent/soloExecutor)
           :send (pool-counts clojure.lang.Agent/pooledExecutor)}})

(defn- in-flight [counts] (if counts (+ (:active counts) (:queued counts)) 0))

(defn pool-in-flight
  "Active plus queued tasks over both Agent pools in a snapshot."
  [snap]
  (reduce + (map in-flight (vals (:pools snap)))))

(def ^:private library-frame
  #"^(clojure\.(core|lang|main|test)|java\.|jdk\.|sun\.|kaocha\.|nrepl\.)")

(defn- frame-text [^StackTraceElement f]
  (str (clojure.lang.Compiler/demunge (.getClassName f)) " (" (.getFileName f) ":" (.getLineNumber f) ")"))

(defn- idle-worker?
  "A pool worker parked waiting for its next task."
  [stack]
  (some #(and (= "java.util.concurrent.ThreadPoolExecutor" (.getClassName ^StackTraceElement %))
              (= "getTask" (.getMethodName ^StackTraceElement %)))
        stack))

(defn- thread-entry [^ThreadInfo info]
  (let [stack (.getStackTrace info)
        frame (or (first (remove #(re-find library-frame (.getClassName ^StackTraceElement %)) stack))
                  (first stack))]
    (cond-> (array-map :name (.getThreadName info) :state (str (.getThreadState info)))
      frame (assoc :at (frame-text frame)))))

(defn- infos [ids depth]
  (when (seq ids)
    (remove nil? (.getThreadInfo mx (long-array ids) (int depth)))))

(defn- task-code?
  "A stack with a frame outside the JVM, Clojure, Kaocha and nREPL: a task's own code is
  running (a worker between tasks has none)."
  [stack]
  (some #(not (re-find library-frame (.getClassName ^StackTraceElement %))) stack))

(defn- busy-pool-threads
  "Agent pool workers running a task's code, with its first frame. Read only once the
  counters say a task is in flight; a worker that finished its task between the counter
  read and this one is not busy (the second observation, not a wait)."
  []
  (let [pool-ids (for [^ThreadInfo i (.dumpAllThreads mx false false)
                       :when (re-find pool-name (.getThreadName i))]
                   (.getThreadId i))]
    (vec (for [^ThreadInfo i (infos pool-ids 64)
               :let [stack (.getStackTrace i)]
               :when (and (not (idle-worker? stack)) (task-code? stack))]
           (thread-entry i)))))

;; INTENT: LOOP-LEAK-REPORT-009
(defn leak
  "The leak a test left, from its `before` and `after` snapshots, or nil when it left
  none. `allow` is a zero-argument fn returning the thread-name regexes (called only
  when a thread was born during the test). {:pools {:send-off {:submitted n
  :in-flight m}} :threads [running pool workers] :new-threads [raw threads born and
  alive] :allowed n}."
  [before after allow]
  (let [pools (into {}
                    (for [[k b] (:pools before)
                          :let [a (get-in after [:pools k])]
                          :when (and a b)
                          :let [submitted (- (:submitted a) (:submitted b))]
                          :when (and (pos? submitted) (> (in-flight a) (in-flight b)))]
                      [k {:submitted submitted :in-flight (in-flight a)}]))
        ^longs old (:threads before)
        born (into [] (remove #(>= (Arrays/binarySearch old (long %)) 0)) (:threads after))
        born-infos (infos born 24)
        allowed? (fn [^ThreadInfo i] (some #(re-find % (.getThreadName i)) (allow)))
        candidates (remove #(or (re-find pool-name (.getThreadName ^ThreadInfo %))
                                (idle-worker? (.getStackTrace ^ThreadInfo %)))
                           born-infos)
        allowed (count (filter allowed? candidates))
        new-threads (mapv thread-entry (remove allowed? candidates))
        busy (when (seq pools) (busy-pool-threads))
        ;; Counters alone are approximate and race the end of a task: a pool leak also
        ;; needs a worker that is running task code now.
        pools (if (seq busy) pools {})]
    (when (or (seq pools) (seq new-threads))
      {:pools pools
       :threads (or busy [])
       :new-threads new-threads
       :allowed allowed})))

;; ---------------------------------------------------------------- hooks

;; The previous leaf's leak, while its work may still be in flight: {:test id :leak ...}
(defonce ^:private last-leak (atom nil))

(def ^:private allow-patterns
  (memoize (fn [extra] (mapv re-pattern (concat default-allow-threads extra)))))

(defn- still-in-flight
  "How much of `prior`'s work is still running now: pool workers running task code (when
  the counters still show tasks in flight; the same second observation as `leak`), plus
  its leaked raw threads still alive."
  [prior snap]
  (let [alive (set (seq ^longs (:threads snap)))
        threads (count (filter #(contains? alive %) (:thread-ids prior)))
        pool (if (and (seq (get-in prior [:leak :pools])) (pos? (pool-in-flight snap)))
               (count (busy-pool-threads))
               0)]
    (+ pool threads)))

;; INTENT: LOOP-LEAK-REPORT-009
;; INTENT: LOOP-LEAK-VERDICT-010
(defn before-test
  "pre-test for a leaf: snapshot, and mark inherited in-flight work from the previous
  leaker."
  [testable]
  (let [t0 (System/nanoTime)
        snap (snapshot)
        prior @last-leak
        n (when prior (still-in-flight prior snap))]
    (when (and prior (not (pos? n))) (reset! last-leak nil))
    (cond-> (assoc testable ::before snap)
      (and prior (pos? n)) (assoc ::inherited {:from (:test prior) :in-flight n})
      true (assoc ::check-nanos (- (System/nanoTime) t0)))))

(declare describe)

(defn- leak-event [testable found]
  (let [m (:kaocha.testable/meta testable)
        running (first (concat (:threads found) (:new-threads found)))]
    {:type :fail
     :message (str "background work still running when the test returned: " (describe found)
                   (when (:at running) (str "; running " (:name running) " at " (:at running)))
                   " (kaocha.plugin.leak-detection/fail-on-leak)")
     :file (:file m) :line (:line m)
     :kaocha/testable testable}))

;; INTENT: LOOP-LEAK-REPORT-009
;; INTENT: LOOP-LEAK-VERDICT-010
(defn after-test
  "post-test for a leaf: compare with the pre-test snapshot; record the leak on the
  testable (::leak), remember it for the next leaf, mark a failing inheritor
  ::contaminated-by, and with fail-on-leak add exactly one fail event."
  [testable config]
  (if-let [before (::before testable)]
    (let [t0 (System/nanoTime)
          after (snapshot)
          found (leak before after #(allow-patterns (::allow-threads config)))
          opted-out? (boolean (get-in testable [:kaocha.testable/meta :kaocha.leak/allow]))
          id (:kaocha.testable/id testable)
          testable (dissoc testable ::before)
          testable (cond-> testable
                     found (assoc ::leak (cond-> found opted-out? (assoc :opted-out true))))
          _ (when (and found (not opted-out?))
              (reset! last-leak {:test id :leak found
                                 :thread-ids (let [born (set (remove #(>= (Arrays/binarySearch ^longs (:threads before) (long %)) 0)
                                                                     (:threads after)))]
                                               born)}))
          strict? (and found (not opted-out?) (true? (::fail-on-leak config)))
          testable (if strict?
                     (let [e (leak-event testable found)]
                       (binding [testable/*fail-fast?* false] (t/do-report e))
                       (-> testable
                           (update :kaocha.result/fail (fnil inc 0))
                           (update :kaocha.testable/events (fnil conj []) e)))
                     testable)
          testable (cond-> testable
                     (and (::inherited testable) (result/failed-one? testable))
                     (assoc ::contaminated-by (get-in testable [::inherited :from])))]
      (update testable ::check-nanos (fnil + 0) (- (System/nanoTime) t0)))
    testable))

(defplugin kaocha.plugin/leak-detection
  (pre-run [test-plan]
           (reset! last-leak nil)
           test-plan)
  (pre-test [testable _test-plan]
            (if (hierarchy/leaf? testable) (before-test testable) testable))
  (post-test [testable test-plan]
             (if (hierarchy/leaf? testable) (after-test testable test-plan) testable)))

;; ---------------------------------------------------------------- reading a result tree

(defn- test-name [id]
  (if (and (keyword? id) (namespace id)) (str (namespace id) "/" (name id)) (some-> id name)))

(defn leak-record
  "A leaf's leak as receipt data, or nil."
  [node]
  (when-let [l (::leak node)]
    (->> [:test (test-name (:kaocha.testable/id node))
          :pools (not-empty (:pools l))
          :threads (not-empty (:threads l))
          :new-threads (not-empty (:new-threads l))
          :allowed (when (pos? (:allowed l 0)) (:allowed l))
          :opted-out (:opted-out l)]
         (partition 2) (remove (comp nil? second)) (apply concat) (apply array-map))))

(defn describe
  "Short text of a leak record: `send-off: 1 in flight of 2 submitted; thread x`."
  [record]
  (str/join "; " (concat (for [[k {:keys [submitted in-flight]}] (:pools record)]
                           (str (name k) ": " in-flight " in flight of " submitted " submitted"))
                         (for [t (:new-threads record)] (str "thread " (:name t) " " (:state t))))))
