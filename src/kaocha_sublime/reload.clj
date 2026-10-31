(ns kaocha-sublime.reload
  "Reload one namespace so the warm image matches its file, without remove-ns.

  `(require ns :reload)` re-evaluates a file but never removes what the file no longer
  says: a deleted or moved definition stays interned, callers keep calling it, and a
  warm check passes on code the files do not contain. Clojure's compiler gives every
  `def` (defn, def, declare, defonce, defmulti, defprotocol, defrecord, deftest ...) a
  fresh metadata map each time the form is evaluated, so a var whose metadata map is
  the very object it was before the reload was not defined by this evaluation of the
  file. Those vars, when their :file names this namespace's file, are unmapped.

  Never remove-ns: defonce state, protocol vars and every var the file still defines
  keep their identity (compiled callers hold Var objects).")

;; INTENT: LOOP-RELOAD-UNMAP-013
(defn interned-meta
  "{sym metadata-map} for every var interned in the namespace named `ns-sym`, nil when
  the namespace does not exist. Reads the namespace's mappings directly (no ns-interns
  map is built): this runs once per reloaded namespace on every check."
  [ns-sym]
  (when-let [^clojure.lang.Namespace ns (find-ns ns-sym)]
    (persistent!
     (reduce-kv (fn [acc sym v]
                  (if (and (instance? clojure.lang.Var v)
                           (identical? ns (.ns ^clojure.lang.Var v)))
                    (assoc! acc sym (meta v))
                    acc))
                (transient {})
                (.getMappings ns)))))

(defn- file-names?
  "True when a var's :file metadata (a classpath-relative resource path such as
  \"my/app/core.clj\" for a required file, or an absolute path for load-file) names
  `source`, the namespace's file. With no known source, any real :file counts."
  [file-meta ^java.io.File source]
  (and (string? file-meta) (not= "NO_SOURCE_PATH" file-meta)
       (or (nil? source)
           (let [path (.getPath source)
                 rel (.replace ^String file-meta \\ \/)]
             (or (= path rel)
                 (.endsWith (.replace path \\ \/) (str "/" rel)))))))

;; INTENT: LOOP-RELOAD-UNMAP-013
(defn unmap-stale!
  "After a successful reload of `ns-sym`, given `before` (its `interned-meta` taken just
  before the reload) and `source` (its file, or nil): every var still interned whose
  metadata map is identical to the one recorded was not defined by the reload.
  - its :file names `source` (the file used to define it): ns-unmap it -> :unmapped
  - it has another real :file (defined into this namespace by some other file): keep -> :kept
  - no :file (runtime intern, stub, protocol method var): keep, counted in :dynamic
  Entries are {:ns :var :file :line :var <the Var object>}; :var-object is for the
  caller's referrer sweep and is not printable data."
  [ns-sym before ^java.io.File source]
  (let [ns (find-ns ns-sym)
        ;; A file-system call: made only once a var turns out not to be redefined.
        source (delay (some-> source .getCanonicalFile))]
    (reduce-kv
     (fn [acc sym m]
       (let [v (when ns (.getMapping ^clojure.lang.Namespace ns sym))]
         (if (and (instance? clojure.lang.Var v)
                  (identical? ns (.ns ^clojure.lang.Var v))
                  (identical? m (meta v)))
           (let [entry {:ns ns-sym :var sym :file (:file m) :line (:line m) :var-object v}]
             (cond
               (file-names? (:file m) @source) (do (ns-unmap ns sym) (update acc :unmapped conj entry))
               (and (string? (:file m)) (not= "NO_SOURCE_PATH" (:file m))) (update acc :kept conj entry)
               :else (update acc :dynamic inc)))
           acc)))
     {:unmapped [] :kept [] :dynamic 0}
     (or before {}))))

;; INTENT: LOOP-RELOAD-UNMAP-013
(defn reload!
  "(require ns-sym :reload), then unmap the vars the file used to define and no longer
  does (`unmap-stale!`). Throws what require throws; a failed reload unmaps nothing (a
  half-evaluated file is not evidence of what it defines). The result carries :nanos,
  the time spent recording and unmapping (outside require), so the cost is a fact."
  [ns-sym source]
  (let [t0 (System/nanoTime)
        before (interned-meta ns-sym)
        t1 (System/nanoTime)]
    (require ns-sym :reload)
    (let [t2 (System/nanoTime)
          r (unmap-stale! ns-sym before source)]
      (assoc r :nanos (+ (- t1 t0) (- (System/nanoTime) t2))))))
