(ns kaocha-sublime.runner
  "The in-JVM half of bin/sublime-loop: one owned, test-only nREPL per project root.
  Started by a repo's `:sublime` alias with `-m kaocha-sublime.runner`, cwd = the
  project root. Never load this runner into an application JVM.

  Contract with bin/sublime-loop:
  - `identity-data` {:root :pid :generation :role \"test-only\"}
  - `(check! [\"ns-name\" ...])` -> {:status \"pass\"|\"fail\"|\"unknown\" :counts ...}
  - `(write-json value)` -> JSON string (the CLI's transport receipt encoder)
  - `-main` writes `.dev-loop/session.json` atomically once nREPL listens."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.namespace.dependency :as dependency]
   [clojure.tools.namespace.dir :as scan]
   [clojure.tools.namespace.track :as track]
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

(defn check!
  "Reload the declared dependency closure of the requested namespaces, then run
  exactly those namespaces through the Sublime probe."
  [namespaces]
  (locking runner-lock
    (let [requested (mapv symbol namespaces)
          base {:image-identity "unknown" :mode "development-probe"}
          scanned (try
                    (let [tracker (scan/scan-dirs (track/tracker) (source-dirs))
                          graph (::track/deps tracker)
                          closure (into (set requested)
                                        (mapcat #(dependency/transitive-dependencies graph %) requested))]
                      {:targets (filterv closure (::track/load tracker))})
                    (catch Throwable t {:error t}))]
      (if-let [t (:error scanned)]
        (assoc base :status "fail" :stage "dependency-scan" :error (.getMessage ^Throwable t))
        (let [targets (:targets scanned)
              ;; Explicit :reload in dependency order (curtaincall variant) rather than
              ;; handing source namespaces to the probe as focus targets (CD variant):
              ;; edited sources are reloaded for certain, and the probe scope and its
              ;; receipt stay exactly the namespaces the caller asked for.
              reload-error (try (doseq [target targets] (require target :reload)) nil
                                (catch Throwable t t))]
          (if reload-error
            ;; No counts: the CLI keeps the runner tainted because a partial reload
            ;; can leave the image half-loaded.
            (assoc base :status "fail" :stage "reload" :error (.getMessage ^Throwable reload-error)
                   :reload-targets (mapv str targets))
            (let [result (probe/run requested
                                    {:plugins [:kaocha.plugin/filter :kaocha.plugin/run-receipt]
                                     :kaocha.plugin.run-receipt/dir ".dev-loop/kaocha-runs"
                                     :kaocha.plugin.run-receipt/include-tests? true
                                     :kaocha/fail-fast? false})
                  receipt (when (:path result) (edn/read-string (slurp (:path result))))
                  counts (:totals receipt)]
              (assoc base
                     ;; probe exit 0 already requires complete coverage and >0 tests
                     ;; (protocol/outcome), which subsumes the template's count>0 guard.
                     :status (case (:exit result) 0 "pass" 1 "fail" "unknown")
                     :stage "probe" :exit (:exit result) :path (:path result)
                     :events (:events result) :counts counts
                     :reload-targets (mapv str targets)
                     :error (some-> ^Throwable (:error result) .getMessage)))))))))

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
