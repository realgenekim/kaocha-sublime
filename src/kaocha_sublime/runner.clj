(ns kaocha-sublime.runner
  "The in-JVM half of bin/sublime-loop: one owned, test-only nREPL per project root.
  Started by a repo's `:sublime` alias with `-m kaocha-sublime.runner`, cwd = the
  project root. Never load this runner into an application JVM.

  Contract with bin/sublime-loop:
  - `identity-data` {:root :pid :generation :role \"test-only\"}
  - `(check! [\"ns-name\" ...])` -> {:status \"pass\"|\"fail\"|\"unknown\" :counts ...}
    (a failed reload adds :reload-failure, :not-reloaded and :jvm-usable)
  - `(check! names {:reload bool :perf bool :slowest n :timings bool})` the same, with
    options (see `check!`); a run adds :slowest, and :test-timings / :perf-tests on request
  - `(write-json value)` -> JSON string (the CLI's transport receipt encoder)
  - `-main` writes `.dev-loop/session.json` atomically once nREPL listens."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.main :as main]
   [clojure.string :as str]
   [clojure.tools.namespace.dependency :as dependency]
   [clojure.tools.namespace.dir :as scan]
   [clojure.tools.namespace.track :as track]
   [kaocha.hierarchy :as hierarchy]
   [kaocha.config :as config]
   [kaocha.testable :as testable]
   [kaocha-sublime.agent :as agent]
   [kaocha-sublime.probe :as probe]
   [nrepl.server :as nrepl]))

(defonce identity-data
  {:root (.getCanonicalPath (io/file "."))
   :pid (.pid (java.lang.ProcessHandle/current))
   :generation (System/getenv "DEV_LOOP_GENERATION")
   :role "test-only"})

(defonce runner-lock (Object.))

;; The vendored copies required clojure.data.json, which neither this library nor
;; Kaocha provides; a minimal encoder keeps the repo alias to nrepl + tools.namespace.
(defn- json-string [s]
  (let [sb (StringBuilder. "\"")]
    (doseq [c (str s)]
      (case c
        \" (.append sb "\\\"") \\ (.append sb "\\\\")
        \newline (.append sb "\\n") \return (.append sb "\\r") \tab (.append sb "\\t")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (str (.append sb "\""))))

(defn write-json
  "Encode maps, sequentials, sets, strings, keywords, symbols, numbers, booleans and
  nil as JSON. Other values are encoded as their string form."
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (str v)
    (or (instance? Long v) (instance? Integer v) (instance? Short v)
        (instance? Byte v) (instance? java.math.BigInteger v)
        (instance? clojure.lang.BigInt v)) (str v)
    (number? v) (let [d (double v)]
                  (if (or (Double/isNaN d) (Double/isInfinite d)) (json-string (str v)) (str d)))
    (string? v) (json-string v)
    (keyword? v) (json-string (if-let [n (namespace v)] (str n "/" (name v)) (name v)))
    (symbol? v) (json-string (str v))
    (map? v) (str "{" (str/join "," (map (fn [[k val]]
                                           (str (write-json (if (or (keyword? k) (symbol? k) (string? k))
                                                              k (pr-str k)))
                                                ":" (write-json val)))
                                         v)) "}")
    (or (sequential? v) (set? v)) (str "[" (str/join "," (map write-json v)) "]")
    :else (json-string (str v))))

(defn- local-roots
  "Canonical dirs of the :local/root deps declared in `dir`'s deps.edn."
  [^java.io.File dir]
  (let [f (io/file dir "deps.edn")]
    (when (.isFile f)
      (->> (vals (:deps (edn/read-string (slurp f))))
           (keep :local/root)
           (map #(.getCanonicalFile (io/file dir %)))))))

(defn local-root-source-dirs
  "The :paths dirs of every :local/root dep reachable from `root`'s deps.edn, followed
  transitively, excluding `root` itself. A sibling root edited by another checkout or
  seat must reload too, or a test runs against the copy loaded when the JVM started."
  [root]
  (let [root (.getCanonicalFile (io/file root))]
    (loop [pending (vec (local-roots root)) seen #{root} dirs []]
      (if-let [dir (first pending)]
        (if (seen dir)
          (recur (subvec pending 1) seen dirs)
          (let [f (io/file dir "deps.edn")
                paths (or (when (.isFile f) (:paths (edn/read-string (slurp f)))) ["src"])]
            (recur (into (subvec pending 1) (local-roots dir))
                   (conj seen dir)
                   (into dirs (map #(io/file dir %) paths)))))
        dirs))))

(defn- source-dirs []
  ;; Scan only directories that exist; a project without test/ must still check.
  (->> (concat [(io/file "src") (io/file "test")] (local-root-source-dirs "."))
       (filter #(.isDirectory ^java.io.File %))
       distinct
       vec))

;; ---------------------------------------------------------------- failure details

(def failure-limit
  "At most this many failure entries per receipt; the rest are counted in
  :failures-omitted."
  10)
(def value-limit "Characters kept of expected, actual, expr and message." 600)
(def output-limit "Characters kept from the END of a test's captured stdout/stderr." 2048)

(defn- clip [^String s limit]
  (when s (if (> (count s) limit) (str (subs s 0 limit) "...") s)))

(defn- tail [^String s limit]
  (when (seq s) (if (> (count s) limit) (str "..." (subs s (- (count s) limit))) s)))

(defn bounded-pr-str
  "pr-str of a test value, bounded so an infinite or deep value cannot hang or
  flood the receipt. Never throws: an unprintable value becomes <unprintable Class>."
  [v]
  (try
    (binding [*print-length* 40 *print-level* 8 *print-meta* false *print-namespace-maps* false]
      (clip (pr-str v) value-limit))
    (catch Throwable _ (str "<unprintable " (.getName (class v)) ">"))))

(defn- throwable-text
  "Class: message {ex-data}, then each cause, then the first stack frame outside
  clojure.core/clojure.lang/java/kaocha (where the test code threw)."
  [^Throwable t]
  (let [chain (take 6 (take-while some? (iterate #(.getCause ^Throwable %) t)))
        line (fn [^Throwable e]
               (str (.getName (class e)) ": " (.getMessage e)
                    (when-let [d (ex-data e)] (str " " (bounded-pr-str d)))))
        frame (->> chain (mapcat #(.getStackTrace ^Throwable %))
                   (remove #(re-find #"^(clojure\.(core|lang|main|test)|java\.|jdk\.|sun\.|kaocha\.|nrepl\.)"
                                     (.getClassName ^StackTraceElement %)))
                   first)]
    (clip (str (str/join " | caused by " (map line chain))
               (when frame (str " | at " (clojure.lang.Compiler/demunge (.getClassName ^StackTraceElement frame)) " ("
                                (.getFileName ^StackTraceElement frame) ":"
                                (.getLineNumber ^StackTraceElement frame) ")")))
          value-limit)))

(defn- unique-by-name
  "The one file named like `file` (a bare name, as clojure.test reports a stack
  frame) under the source dirs, or nil when there is none or more than one."
  [file]
  (when (and (string? file) (= file (.getName (io/file file))))
    (let [hits (->> (source-dirs) (mapcat file-seq)
                    (filter #(and (.isFile ^java.io.File %) (= file (.getName ^java.io.File %))))
                    (take 2))]
      (when (= 1 (count hits)) (.getCanonicalFile ^java.io.File (first hits))))))

(defn- root-relative
  "The source file of a failure as a path relative to the project root when it is
  found there: the event's own file, else the var's classpath-relative :file under a
  source dir, else the one file under the source dirs with the event's bare name.
  Falls back to whatever the event said."
  [file meta-file]
  (let [root (.getCanonicalFile (io/file "."))
        under-root (fn [f] (let [c (.getCanonicalFile (io/file f))]
                             (when (.isFile c) c)))
        same-name? (and (string? file) (string? meta-file)
                        (= (.getName (io/file file)) (.getName (io/file meta-file))))
        found (or (when (string? file) (under-root file))
                  (when (or same-name? (nil? file))
                    (some #(when (string? meta-file) (under-root (io/file % meta-file))) (source-dirs)))
                  (unique-by-name file))]
    (if (and found (str/starts-with? (str found) (str root java.io.File/separator)))
      (str (.relativize (.toPath root) (.toPath found)))
      (or file meta-file))))

(defn- failure-entry [node e]
  (let [id (:kaocha.testable/id node)
        m (:kaocha.testable/meta node)
        error? (hierarchy/error-type? e)
        actual (:actual e)
        eq (@#'agent/equality-values e)
        contexts (seq (reverse (:testing-contexts e)))
        file (root-relative (:file e) (:file m))
        line (or (:line e) (:line m))
        line (when (and (integer? line) (pos? line)) line)]
    ;; array-map keeps this key order in the JSON, for readers scanning by eye.
    (->> [:kind (cond (:kaocha.testable/load-error node) "load" error? "error" :else "fail")
          :test (if (and (keyword? id) (namespace id)) (str (namespace id) "/" (name id)) (some-> id name))
          :at (when file (str file (when line (str ":" line))))
          :file file
          :line line
          :message (or (some-> (:message e) str (clip value-limit))
                       ;; Kaocha's own failure types carry no message; name them.
                       (when (hierarchy/isa? (:type e) :kaocha.type.var/zero-assertions)
                         "Test ran without assertions. Did you forget an (is ...)?")
                       (when-not (contains? #{:fail :error} (:type e)) (str "failure type " (:type e))))
          :context (when contexts (clip (str/join " > " contexts) value-limit))
          :expr (when (and (contains? e :expected) (not error?)) (bounded-pr-str (:expected e)))
          :expected (cond eq (bounded-pr-str (first eq))
                          (and (contains? e :expected) (not error?)) (bounded-pr-str (:expected e)))
          :actual (cond (instance? Throwable actual) (throwable-text actual)
                        eq (bounded-pr-str (second eq))
                        (contains? e :actual) (bounded-pr-str actual))
          :output (tail (:kaocha.plugin.capture-output/output node) output-limit)]
         (partition 2)
         (remove (fn [[_ v]] (or (nil? v) (= "" v))))
         (apply concat)
         (apply array-map))))

;; INTENT: LOOP-FAILURE-DETAILS-001
(defn failure-details
  "Every failing or erroring assertion in a Kaocha result tree, in run order:
  {:failures [first `failure-limit` entries] :failures-omitted n}. Each entry has
  :kind :test :at :file :line :message :context :expr :expected :actual :output
  (blank fields left out). Values are bounded pr-str (`value-limit` chars); :output
  is the test's captured stdout+stderr, last `output-limit` chars."
  [result]
  (let [entries (for [node (testable/test-seq result)
                      e (:kaocha.testable/events node)
                      :when (hierarchy/fail-type? e)]
                  (failure-entry node e))
        shown (vec (take failure-limit entries))]
    {:failures shown
     :failures-omitted (- (count entries) (count shown))}))

;; INTENT: LOOP-VAR-FOCUS-002
(defn- missing-test-vars
  "Qualified targets that name no test var once their namespace is loaded."
  [requested]
  (vec (for [sym requested
             :when (namespace sym)
             :let [v (try (find-var sym) (catch Exception _ nil))]
             :when (not (and v (:test (meta v))))]
         (str sym))))

;; INTENT: LOOP-RELOAD-FAILURE-005
(defn jvm-usable?
  "False when a reload failure says the JVM itself is in trouble (a VirtualMachineError
  such as OutOfMemoryError anywhere in the cause chain), not merely that one namespace
  failed to read, compile or load."
  [^Throwable t]
  (not-any? #(instance? VirtualMachineError %)
            (take 10 (take-while some? (iterate #(.getCause ^Throwable %) t)))))

(defn reload-failure
  "What a failed (require ns :reload) tells a reader: the namespace, the source file
  relative to the root and the line/column of the error, the phase (read-source,
  compile-syntax-check, macroexpansion, execution ...) and the message."
  [ns-sym ^Throwable t ns-file]
  (let [triage (try (main/ex-triage (Throwable->map t)) (catch Throwable _ {}))
        path (:clojure.error/path triage)
        ns-path (when ns-file
                  (let [root (.getCanonicalFile (io/file "."))
                        f (.getCanonicalFile (io/file ns-file))]
                    (str (.relativize (.toPath root) (.toPath f)))))
        file (or (when path (let [r (root-relative nil path)] (when (not= r path) r)))
                 ns-path path)
        line (:clojure.error/line triage)
        line (when (and (integer? line) (pos? line)) line)]
    (->> [:namespace (str ns-sym)
          :at (when file (str file (when line (str ":" line))))
          :file file
          :line line
          :column (:clojure.error/column triage)
          :phase (some-> (:clojure.error/phase triage) name)
          :message (clip (or (:clojure.error/cause triage) (.getMessage t) (.getName (class t))) value-limit)
          :class (.getName (class (or (some-> t .getCause) t)))]
         (partition 2)
         (remove (comp nil? second))
         (apply concat)
         (apply array-map))))

;; ---------------------------------------------------------------- timings

(def slowest-default "How many of the slowest test vars a check receipt names." 5)

(defn- test-name [id]
  (if (and (keyword? id) (namespace id)) (str (namespace id) "/" (name id)) (some-> id name)))

(defn- var-outcome [node]
  (let [n #(or (get node %) 0)]
    (cond (pos? (n :kaocha.result/error)) "error"
          (pos? (n :kaocha.result/fail)) "fail"
          (pos? (n :kaocha.result/pending)) "pending"
          :else "pass")))

;; INTENT: LOOP-RECEIPT-LOAD-SLOWEST-006
(defn test-timings
  "Every test var that ran in a Kaocha result tree, in run order:
  {:test \"ns/var\" :ms duration :outcome \"pass\"|\"fail\"|\"error\"|\"pending\"}.
  The duration is Kaocha's profiling plugin's, in milliseconds to 0.1 ms; skipped vars
  and vars without a recorded duration are left out."
  [result]
  (vec (for [node (testable/test-seq result)
             :when (= :kaocha.type/var (:kaocha.testable/type node))
             :when (not (:kaocha.testable/skip node))
             :let [nanos (:kaocha.plugin.profiling/duration node)]
             :when (integer? nanos)]
         (array-map :test (test-name (:kaocha.testable/id node))
                    :ms (/ (Math/round (/ (double nanos) 1e5)) 10.0)
                    :outcome (var-outcome node)))))

(defn slowest
  "The `n` slowest entries of `timings`, slowest first."
  [timings n]
  (vec (take (max 0 n) (sort-by :ms > timings))))

;; ---------------------------------------------------------------- perf selection

;; INTENT: LOOP-PERF-MODE-008
(defn perf-vars
  "The ^:perf test vars a perf check runs, as qualified symbols: for a namespace target
  every var with both :test and :perf metadata (in line order); for a var target, that
  var when it carries :perf. Namespaces must already be loaded."
  [targets]
  (vec (distinct
        (for [sym targets
              v (if (namespace sym)
                  (some-> (try (find-var sym) (catch Exception _ nil)) vector)
                  (some->> (find-ns sym) ns-interns vals (sort-by #(:line (meta %) 0))))
              :let [m (meta v)]
              :when (and (:test m) (:perf m))]
          (symbol (str (:ns m)) (str (:name m)))))))

(defn- test-namespaces
  "The namespaces of the default (:unit) suite in a scan: files under its test paths
  whose names match its ns-patterns. What a perf check runs when no namespace is named."
  [filemap]
  (let [suite (or (first (filter #(= :unit (:kaocha.testable/id %))
                                 (:kaocha/tests (config/load-config
                                                 (config/find-config-and-warn "tests.edn") {}))))
                  {})
        paths (map #(str (.getCanonicalPath (io/file %)) java.io.File/separator)
                   (or (:kaocha/test-paths suite) ["test"]))
        patterns (map re-pattern (or (:kaocha/ns-patterns suite) ["-test$"]))]
    (vec (sort (for [[^java.io.File f ns] filemap
                     :when (some #(str/starts-with? (.getCanonicalPath f) %) paths)
                     :when (some #(re-find % (str ns)) patterns)]
                 ns)))))

(defn check!
  "Reload the declared dependency closure of the requested namespaces, then run
  exactly those targets through the Sublime probe. A target is a namespace
  (\"my.app-test\") or one test var (\"my.app-test/adds\"); a var's namespace is
  reloaded as if it were named.

  Options: :reload false skips the dependency-closure reload (a repeat run over
  unchanged files; the probe still reloads the target test namespaces themselves);
  :perf true runs only the ^:perf test vars of the targets (all of the default suite's
  test namespaces when none are named), un-skipping :perf, and adds :perf-tests;
  :slowest n names the n slowest test vars (default `slowest-default`); :timings true
  adds :test-timings, every var that ran with its outcome and duration."
  ([namespaces] (check! namespaces {}))
  ([namespaces {:keys [reload perf timings] :or {reload true} :as opts}]
   (locking runner-lock
     (let [requested (mapv symbol namespaces)
           slowest-n (if (integer? (:slowest opts)) (:slowest opts) slowest-default)
           base {:image-identity "unknown" :mode "development-probe"}
           scanned (try
                     (let [tracker (scan/scan-dirs (track/tracker) (source-dirs))
                           graph (::track/deps tracker)
                           filemap (:clojure.tools.namespace.file/filemap tracker)
                           requested (if (and perf (empty? requested)) (test-namespaces filemap) requested)
                           requested-ns (vec (distinct (map #(symbol (or (namespace %) (name %))) requested)))
                           closure (into (set requested-ns)
                                         (mapcat #(dependency/transitive-dependencies graph %) requested-ns))]
                       {:targets (if reload (filterv closure (::track/load tracker)) [])
                        :requested requested
                        :files (into {} (map (fn [[f ns]] [ns f])) filemap)})
                     (catch Throwable t {:error t}))
           requested (or (:requested scanned) requested)
           base (cond-> base (not reload) (assoc :reloaded false))]
       (if-let [t (:error scanned)]
         ;; A scan only reads files; nothing was loaded, so the JVM stays usable.
         (assoc base :status "fail" :stage "dependency-scan" :error (.getMessage ^Throwable t)
                :jvm-usable true)
         (let [targets (:targets scanned)
               ;; Explicit :reload in dependency order (curtaincall variant) rather than
               ;; handing source namespaces to the probe as focus targets (CD variant):
               ;; edited sources are reloaded for certain, and the probe scope and its
               ;; receipt stay exactly the namespaces the caller asked for.
               reload-error (reduce (fn [_ target]
                                      (try (require target :reload) nil
                                           (catch Throwable t (reduced {:namespace target :error t}))))
                                    nil targets)]
           (if reload-error
             ;; No counts and never a pass: nothing ran. The failed namespace (and the
             ;; ones after it) keep their previous definitions until the next check, which
             ;; reloads the whole closure again and so repairs them; the JVM stays warm
             ;; unless the error says the JVM itself is in trouble (jvm-usable false).
             (let [failure (reload-failure (:namespace reload-error) (:error reload-error)
                                           (get (:files scanned) (:namespace reload-error)))]
               (assoc base :status "fail" :stage "reload"
                      :error (str "reload of " (:namespace failure) " failed"
                                  (when (:at failure) (str " at " (:at failure))) ": " (:message failure))
                      :reload-failure failure
                      :failures [(-> (select-keys failure [:at :file :line :message])
                                     (assoc :kind "load" :test (:namespace failure)))]
                      :failures-omitted 0
                      :not-reloaded (mapv str (drop-while #(not= % (:namespace reload-error)) targets))
                      :jvm-usable (jvm-usable? (:error reload-error))
                      :reload-targets (mapv str targets)))
             (let [missing (seq (missing-test-vars requested))
                   selected (when (and perf (not missing)) (perf-vars requested))]
               (cond
                 missing
                 ;; A typo'd var would focus on nothing and come back "unknown" with zero
                 ;; tests; say exactly which name is wrong instead.
                 (assoc base :status "fail" :stage "focus"
                        :error (str "no such test var: " (str/join ", " missing))
                        :reload-targets (mapv str targets))

                 (and perf (empty? selected))
                 (assoc base :status "fail" :stage "focus"
                        :error (str "no ^:perf test vars in: "
                                    (if (seq requested) (str/join ", " requested) "the default suite's test namespaces"))
                        :perf-selected []
                        :reload-targets (mapv str targets))

                 :else
                 (let [result (probe/run (if perf selected requested)
                                         (cond-> {:plugins [:kaocha.plugin/filter :kaocha.plugin/run-receipt
                                                            :kaocha.plugin/profiling]
                                                  :kaocha.plugin.profiling/profiling? false
                                                  :kaocha.plugin.run-receipt/dir ".dev-loop/kaocha-runs"
                                                  :kaocha.plugin.run-receipt/include-tests? true
                                                  ;; Never fail-fast: every timing and every flake is reported.
                                                  :kaocha/fail-fast? false}
                                           perf (assoc :kaocha-sublime.probe/allow-meta [:perf])))
                       receipt (when (:path result) (edn/read-string (slurp (:path result))))
                       counts (:totals receipt)
                       details (try (some-> (:result result) failure-details)
                                    (catch Throwable t
                                      {:failures [{:kind "error" :test "kaocha-sublime.runner/failure-details"
                                                   :message (str "could not build failure details: " (.getMessage t))}]
                                       :failures-omitted 0}))
                       timed (try (some-> (:result result) test-timings) (catch Throwable _ nil))]
                   (cond-> (assoc (merge base (when (seq (:failures details)) details))
                                  ;; probe exit 0 already requires complete coverage and >0 tests
                                  ;; (protocol/outcome), which subsumes the template's count>0 guard.
                                  :status (case (:exit result) 0 "pass" 1 "fail" "unknown")
                                  :stage "probe" :exit (:exit result) :path (:path result)
                                  :events (:events result) :counts counts
                                  :reload-targets (mapv str targets)
                                  :slowest (slowest (or timed []) slowest-n)
                                  :error (some-> ^Throwable (:error result) .getMessage))
                     timings (assoc :test-timings (or timed []))
                     perf (assoc :perf-selected (mapv str selected) :perf-tests (or timed [])))))))))))))

(defn -main [& _]
  (let [server (nrepl/start-server :bind "127.0.0.1" :port 0)
        receipt (assoc identity-data :port (:port server))
        dir (doto (io/file ".dev-loop") .mkdirs)
        target (io/file dir "session.json")
        temp (io/file dir "session.tmp")]
    (spit temp (write-json receipt))
    (java.nio.file.Files/move (.toPath temp) (.toPath target)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
    (println "DEV_LOOP_READY" (write-json receipt))
    @(promise)))
