(ns kaocha-sublime.runner
  "The in-JVM half of bin/sublime-loop: one owned, test-only nREPL per project root.
  Started by a repo's `:sublime` alias with `-m kaocha-sublime.runner`, cwd = the
  project root. Never load this runner into an application JVM.

  Contract with bin/sublime-loop:
  - `identity-data` {:root :pid :generation :role \"test-only\"}
  - `(check! [\"ns-name\" ...])` -> {:status \"pass\"|\"fail\"|\"unknown\" :counts ...}
    (a failed reload adds :reload-failure, :not-reloaded and :jvm-usable; a reload adds
    :unmapped and :stale, and a stale image fails at :stage \"stale-image\"; a run adds
    :leaks :leak-count :leak-check-ms :leak-checked)
  - `(check! names {:reload bool :perf bool :slowest n :timings bool :repair-stale bool
    :fail-on-leak bool})` the same, with
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
   [kaocha.plugin.leak-detection :as leak]
   [kaocha.config :as config]
   [kaocha.testable :as testable]
   [kaocha-sublime.agent :as agent]
   [kaocha-sublime.probe :as probe]
   [kaocha-sublime.reload :as reload]
   [kaocha-sublime.stale :as stale]
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

(defn- test-name* [id]
  (if (and (keyword? id) (namespace id)) (str (namespace id) "/" (name id)) (some-> id name)))

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
          :output (tail (:kaocha.plugin.capture-output/output node) output-limit)
          ;; INTENT: LOOP-LEAK-VERDICT-010
          :contaminated-by (some-> (::leak/contaminated-by node) test-name*)
          :contaminated-in-flight (when (::leak/contaminated-by node) (get-in node [::leak/inherited :in-flight]))]
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

(defn- unmapped-entry
  "A var the reload unmapped, as receipt data: {:ns :var :at} where :at is where the
  file used to define it (root-relative when found)."
  [{:keys [ns var file line]}]
  (let [f (when file (root-relative nil file))]
    (->> [:ns (str ns) :var (str var) :at (when f (str f (when (and (integer? line) (pos? line)) (str ":" line))))]
         (partition 2) (remove (comp nil? second)) (apply concat) (apply array-map))))

;; ---------------------------------------------------------------- stale image

(defn- reload-closure
  "Reload `targets` in order through `reload/reload!`, with Clojure's stderr copied into
  `buffer` (and still written to stderr). {:unmapped [..] :kept [..] :dynamic n
  :nanos <time spent recording and unmapping, outside require>} plus :error
  {:namespace :error} at the first namespace that fails to reload."
  [targets files ^java.io.StringWriter buffer]
  (binding [*err* (stale/tee-writer *err* buffer)]
    (try
      (reduce (fn [acc target]
                (try (let [r (reload/reload! target (get files target))]
                       (-> acc (update :unmapped into (:unmapped r)) (update :kept into (:kept r))
                           (update :dynamic + (:dynamic r)) (update :nanos + (:nanos r))))
                     (catch Throwable t (reduced (assoc acc :error {:namespace target :error t})))))
              {:unmapped [] :kept [] :dynamic 0 :nanos 0} targets)
      (finally (.flush ^java.io.Writer *err*)))))

(defn- rel-file [^java.io.File f]
  (when f
    (let [root (.getCanonicalFile (io/file "."))
          c (.getCanonicalFile f)]
      (if (str/starts-with? (str c) (str root java.io.File/separator))
        (str (.relativize (.toPath root) (.toPath c)))
        (str c)))))

(defn- at [file line] (when file (str file (when (and (integer? line) (pos? line)) (str ":" line)))))

(defn- repair-stale-configured?
  "tests.edn's :kaocha-sublime/repair-stale, read only when a stale image was found."
  []
  (try (let [f (io/file "tests.edn")]
         (when (.isFile f)
           (true? (:kaocha-sublime/repair-stale (edn/read-string {:default (fn [_ v] v)} (slurp f))))))
       (catch Throwable _ false)))

(defn- stale-assessment
  "Checks 1-3 of the stale-image design over the reloaded `targets`."
  [targets scanned loaded rejected]
  (let [files (:files scanned)
        closure (:closure scanned)
        ;; Check 1 only where a refer can have silently failed to take: a namespace that
        ;; interned the referred name, which (when the file no longer defines it) is a
        ;; namespace the reload just unmapped or kept a var in, or one Clojure named in a
        ;; REJECTED line. Anything else fails loudly at reload (IllegalAccessError,
        ;; IllegalStateException) or is a stale var Check 3 finds. No ns form is read
        ;; on a check that unmapped nothing.
        suspects (into (set (map :ns (concat (:unmapped loaded) (:kept loaded))))
                       (map (comp symbol :ns) rejected))
        refers (stale/refer-mismatches (filterv suspects targets) files (set (map :var-object (:kept loaded))))
        unmapped-at (into {} (map (fn [e] [[(:ns e) (:var e)] (:at (unmapped-entry e))])) (:unmapped loaded))
        refers (mapv (fn [e]
                       (let [said (some #(when (and (= (:ns %) (str (:ns e))) (= (:kept %) (str (:ns e) "/" (:sym e))))
                                           (:line %)) rejected)
                             was (get unmapped-at [(:ns e) (:sym e)])]
                         (cond-> e said (assoc :clojure-said said) was (assoc :defined-at was))))
                     refers)
        referrers (mapv #(assoc % :in-closure? (contains? closure (:ns %)))
                        (stale/stale-referrers (map :var-object (:unmapped loaded))))]
    {:refers refers :referrers referrers}))

(defn- stale-failure [files kind e]
  (let [file (rel-file (get files (:ns e)))
        line (when (= kind :refer) (:line e))]
    (->> [:kind "stale"
          :test (str (:ns e) "/" (:sym e))
          :at (at file line) :file file :line line
          :message
          (case kind
            :refer
            (str (case (:class e)
                   "refused" (str "the :refer of " (:home e) " from " (:lib e) " did not take: " (:ns e) " interned "
                                  (:sym e) " itself"
                                  (when (:clojure-said e) " (Clojure REJECTED the refer)")
                                  ", so its code still calls the old definition"
                                  (when (:defined-at e) (str " (was at " (:defined-at e) ")")))
                   "old-var" (str (:ns e) " maps " (:sym e) " to " (:actual e) ", but its ns form refers " (:expected e))
                   "missing" (str (:ns e) " refers " (:home e) ", which " (:lib e) " no longer defines (a cold JVM fails to load it)"))
                 (if (and (= "refused" (:class e)) (:defined-at e))
                   ;; This reload already unmapped the old definition: the next reload's refer takes.
                   ". Repair: check again (the old definition is now unmapped), test-focus --repair-stale, or the cold gate."
                   ". Repair: test-focus --repair-stale, or run the cold gate."))
            :referrer
            (str (:ns e) " maps " (:sym e) " to " (:refers e) ", which " (:home e)
                 "'s file no longer defines (the reload unmapped it). "
                 (if (:in-closure? e)
                   "It is in this check's reload closure but kept the mapping (:refer :all and :use never remove a name). Repair: test-focus --repair-stale, or run the cold gate."
                   (str "It is outside this check's reload closure: name " (:ns e) " in the check, or run the cold gate."))))]
         (partition 2) (remove (comp nil? second)) (apply concat) (apply array-map))))

(defn- refer-data [e]
  (->> [:ns (str (:ns e)) :sym (str (:sym e)) :class (:class e) :lib (str (:lib e)) :home (str (:home e))
        :actual (:actual e) :expected (:expected e) :defined-at (:defined-at e) :clojure-said (:clojure-said e)]
       (partition 2) (remove (comp nil? second)) (apply concat) (apply array-map)))

(defn- referrer-data [e]
  (array-map :ns (str (:ns e)) :sym (str (:sym e)) :refers (:refers e) :home (str (:home e))
             :in-closure? (:in-closure? e)))

;; INTENT: LOOP-STALE-REFER-011
;; INTENT: LOOP-STALE-REPAIR-012
(defn- stale-image!
  "Compare the reloaded image with the files (stale-refer design, Checks 1-3) and, when
  enabled, repair what lies inside the reload closure: ns-unmap the stale mappings,
  reload those namespaces again in dependency order and check again; only a clean
  re-check may pass. Returns {:report <receipt :stale> :failures [stale failure
  entries] :loaded <loaded, plus the repair reload's results> :repair? bool}."
  [targets scanned loaded ^java.io.StringWriter buffer opts]
  (let [started (System/nanoTime)
        files (:files scanned)
        lines (stale/reload-lines (str buffer))
        first-pass (stale-assessment targets scanned loaded (:rejected lines))
        problems? #(or (seq (:refers %)) (seq (:referrers %)))
        repair? (and (problems? first-pass)
                     (if (contains? opts :repair-stale) (true? (:repair-stale opts)) (repair-stale-configured?)))
        check-nanos (- (System/nanoTime) started)
        repair (when repair?
                 (let [t0 (System/nanoTime)
                       before (into {} (for [e (:refers first-pass)]
                                         [[(:ns e) (:sym e)] (:actual e)]))
                       in-closure (filter :in-closure? (:referrers first-pass))
                       _ (doseq [{n :ns sym :sym} (concat (:refers first-pass) in-closure)]
                           (when (var? (some-> (find-ns n) (.getMapping sym))) (ns-unmap n sym)))
                       affected (set (map :ns (concat (:refers first-pass) in-closure)))
                       again (reload-closure (filterv affected targets) files buffer)
                       merged (-> loaded (update :unmapped into (:unmapped again)) (update :kept into (:kept again))
                                  (update :nanos + (:nanos again)) (cond-> (:error again) (assoc :error (:error again))))
                       recheck (when-not (:error again)
                                 ;; Every var unmapped this check is still stale, wherever it is still held.
                                 (stale-assessment targets scanned merged (:rejected (stale/reload-lines (str buffer)))))]
                   {:loaded merged :recheck recheck
                    :ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
                    :repaired (vec (for [e (concat (:refers first-pass) (map #(assoc % :class "referrer") in-closure))
                                         :let [v (some-> (find-ns (:ns e)) (.getMapping (:sym e)))]]
                                     (array-map :ns (str (:ns e)) :var (str (:sym e))
                                                :was (or (get before [(:ns e) (:sym e)]) (:refers e))
                                                :now (when (var? v) (stale/var-label v)))))}))
        final (if repair (or (:recheck repair) {:refers [] :referrers []}) first-pass)
        loaded (if repair (:loaded repair) loaded)
        defs (vec (concat (map #(assoc (unmapped-entry %) :unmapped true) (:unmapped loaded))
                          (map #(assoc (unmapped-entry %) :unmapped false) (:kept loaded))))
        check-ms (/ (Math/round (/ (double (+ check-nanos (:nanos loaded))) 1e4)) 100.0)
        report (->> [:refers (mapv refer-data (:refers final))
                     :referrers (mapv referrer-data (:referrers final))
                     :defs defs
                     :dynamic (when (pos? (:dynamic loaded)) (:dynamic loaded))
                     :rejected (not-empty (mapv :line (:rejected lines)))
                     :reload-warnings (not-empty (:warnings lines))
                     :repaired (:repaired repair)
                     :repair-ms (:ms repair)
                     :check-ms check-ms]
                    (partition 2)
                    (remove (fn [[_ v]] (or (nil? v) (and (coll? v) (empty? v)))))
                    (apply concat) (apply array-map))]
    {:report report :repair? (boolean repair?) :loaded loaded
     :failures (vec (concat (map #(stale-failure files :refer %) (:refers final))
                            (map #(stale-failure files :referrer %) (:referrers final))))}))

;; ---------------------------------------------------------------- leaks

;; INTENT: LOOP-LEAK-REPORT-009
(defn leak-details
  "Leaks in a Kaocha result tree (kaocha.plugin.leak-detection), in run order:
  {:leaks [first `failure-limit` records] :leaks-omitted n :leak-count n
  :leaks-allowed n (opted out with ^:kaocha.leak/allow) :leak-check-ms total
  :leak-checked n (leaf tests observed)}."
  [result]
  (let [leaves (filter #(contains? % ::leak/check-nanos) (testable/test-seq result))
        records (keep leak/leak-record leaves)
        counted (remove :opted-out records)
        shown (vec (take failure-limit counted))
        nanos (reduce + (map ::leak/check-nanos leaves))]
    (cond-> {:leaks shown
             :leaks-omitted (- (count counted) (count shown))
             :leak-count (count counted)
             :leak-check-ms (/ (Math/round (/ (double nanos) 1e4)) 100.0)
             :leak-checked (count leaves)}
      (seq (filter :opted-out records)) (assoc :leaks-allowed (count (filter :opted-out records))))))

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
  adds :test-timings, every var that ran with its outcome and duration; :repair-stale
  true repairs a stale image inside the closure (default: tests.edn's
  :kaocha-sublime/repair-stale, else off); :fail-on-leak true makes each leak one
  failure of the leaking test (default: tests.edn's
  :kaocha.plugin.leak-detection/fail-on-leak, else warn)."
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
                        :closure closure
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
               ;; INTENT: LOOP-RELOAD-UNMAP-013
               ;; Each namespace is reloaded, then the vars its file no longer defines are
               ;; unmapped (never remove-ns), before its dependents reload: a dependent that
               ;; still refers a deleted var fails to load, as it would in a cold JVM.
               buffer (java.io.StringWriter.)
               loaded (reload-closure targets (:files scanned) buffer)
               ;; INTENT: LOOP-STALE-REFER-011
               ;; After the whole loop (a mid-loop check would see homes not yet reloaded).
               image (when (and (not (:error loaded)) (seq targets))
                       (stale-image! targets scanned loaded buffer opts))
               loaded (if image (:loaded image) loaded)
               reload-error (:error loaded)
               base (cond-> base
                      (seq (:unmapped loaded)) (assoc :unmapped (mapv unmapped-entry (:unmapped loaded)))
                      (:report image) (assoc :stale (:report image)))]
           (cond
             reload-error
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

             (seq (:failures image))
             ;; Never a pass, never unknown: the image is wrong, not broken, and the runner
             ;; can name every mapping that differs from the files. The JVM stays warm.
             (assoc base :status "fail" :stage "stale-image"
                    :error (str "stale image: " (count (:failures image))
                                " mapping(s) in the warm JVM do not match the files"
                                (when-not (:repair? image) "; test-focus --repair-stale can repair those inside the closure"))
                    :failures (vec (take failure-limit (:failures image)))
                    :failures-omitted (max 0 (- (count (:failures image)) failure-limit))
                    :jvm-usable true
                    :reload-targets (mapv str targets))

             :else
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
                                         (cond-> {:plugins [:kaocha.plugin/filter
                                                            ;; INTENT: LOOP-LEAK-REPORT-009
                                                            :kaocha.plugin/leak-detection
                                                            :kaocha.plugin/run-receipt
                                                            :kaocha.plugin/profiling]
                                                  :kaocha.plugin.profiling/profiling? false
                                                  :kaocha.plugin.run-receipt/dir ".dev-loop/kaocha-runs"
                                                  :kaocha.plugin.run-receipt/include-tests? true
                                                  ;; Never fail-fast: every timing and every flake is reported.
                                                  :kaocha/fail-fast? false}
                                           perf (assoc :kaocha-sublime.probe/allow-meta [:perf])
                                           ;; INTENT: LOOP-LEAK-VERDICT-010
                                           (:fail-on-leak opts) (assoc ::leak/fail-on-leak true)))
                       receipt (when (:path result) (edn/read-string (slurp (:path result))))
                       counts (:totals receipt)
                       details (try (some-> (:result result) failure-details)
                                    (catch Throwable t
                                      {:failures [{:kind "error" :test "kaocha-sublime.runner/failure-details"
                                                   :message (str "could not build failure details: " (.getMessage t))}]
                                       :failures-omitted 0}))
                       timed (try (some-> (:result result) test-timings) (catch Throwable _ nil))
                       leaks (try (some-> (:result result) leak-details) (catch Throwable _ nil))]
                   (cond-> (assoc (merge base (when (seq (:failures details)) details) leaks)
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
