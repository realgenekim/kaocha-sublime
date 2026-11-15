(ns kaocha-sublime.reload
  "Reload one namespace so the warm image matches its file, without remove-ns.

  `(require ns :reload)` re-evaluates a file but never removes what the file no longer
  says: a deleted or moved definition stays interned, callers keep calling it, and a
  warm check passes on code the files do not contain. Clojure's compiler gives every
  `def` (defn, def, declare, defonce, defmulti, defprotocol, defrecord, deftest ...) a
  fresh metadata map each time the form is evaluated, so a var whose metadata map is
  the very object it was before the reload was not defined by this evaluation of the
  file. Those vars are unmapped only when the file no longer names a definition.

  Only code the file would evaluate names a definition: a form inside `(comment ...)`
  or inside quoted data defines nothing, so a definition moved into a comment is a
  deletion, and the var goes. A bare `comment` counts as the comment form only at the
  top level of a file that has not taken `comment` from clojure.core: inside a body a
  local binding may shadow it, and a file may exclude or redefine it, and then the
  forms inside run. When unsure, the definitions are kept.

  Never remove-ns: defonce state, protocol vars and every var the file still defines
  keep their identity (compiled callers hold Var objects)."
  (:require
   [clojure.java.io :as io]))

;; INTENT: LOOP-RELOAD-UNMAP-018
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

(defn- core-aliases
  "The alias symbols a file's `ns` form gives to clojure.core, read from simple
  `[clojure.core :as c]` / `[clojure.core :as-alias c]` require specs, so that
  `c/comment` can be recognised as `clojure.core/comment`. Any other shape (a prefix
  list, a spec the reader does not hand us as a literal) contributes nothing, which
  leaves a qualified head unresolved and therefore not a comment: the direction that
  keeps the var. `:refer-clojure` is not read - it changes which clojure.core names are
  referred, not what clojure.core is aliased to."
  [form]
  (when (and (seq? form) (= 'ns (first form)))
    (into #{}
          (comp (filter #(and (seq? %) (= :require (first %))))
                (mapcat rest)
                (filter #(and (sequential? %) (= 'clojure.core (first %)) (odd? (count %))))
                (map #(apply hash-map (rest %)))
                (keep #(or (:as %) (:as-alias %)))
                (filter simple-symbol?))
          (rest form))))

(defn- core-form?
  "True when `head` names clojure.core's `n`: the bare symbol, `clojure.core/n`, or
  `<alias>/n` for an alias the file gives clojure.core (`aliases`). A qualified head
  whose namespace is not known to be clojure.core is some other function - a project's
  own `helper/comment` evaluates its arguments - so it is not this form, and a `def`
  inside it is a definition, which is the direction that keeps the var."
  [head n aliases]
  (and (= n (name head))
       (if-let [q (namespace head)]
         (or (= "clojure.core" q) (contains? aliases (symbol q)))
         true)))

(defn- comment-shadowed?
  "True when `form` is an ns form whose :refer-clojure clause may stop the bare symbol
  `comment` meaning clojure.core/comment: any :only list, or `comment` named anywhere in
  the clause (:exclude, :rename). Over-reports on purpose: a shadowed file keeps the
  definitions inside its bare comment forms, which is the safe direction."
  [form]
  (and (seq? form) (= 'ns (first form))
       (boolean
        (some #(and (seq? %) (= :refer-clojure (first %))
                    (or (some #{:only} %)
                        (some #{'comment} (flatten (map (fn [x] (if (map? x) (seq x) x)) (rest %))))))
              (rest form)))))

(defn- definitions-in
  "Adds to `acc` every name the forms in `node` define, at any depth (including inside a
  function body). Quoted data is not descended into, and neither is a comment form: a
  `def` inside one is not a definition. A qualified head (`clojure.core/comment`, or
  through an alias in `aliases`) is a comment form at any depth, since no local can
  shadow a qualified symbol. A bare `comment` head is one only where `bare-comment?` is
  true (the top level of a file that has not shadowed it, and the forms of a top-level
  `do`, which Clojure compiles as top-level forms and which binds nothing): inside any
  other body a `let` may bind `comment` to a function whose arguments run, so the
  definitions are kept."
  [{:keys [aliases bare-comment?] :as ctx} acc node]
  (if-not (coll? node)
    acc
    (let [head (when (and (seq? node) (symbol? (first node))) (first node))
          ctx (assoc ctx :bare-comment? (and bare-comment? (= 'do head)))]
      (if (and head (or (and (core-form? head "comment" aliases)
                             (or (namespace head) bare-comment?))
                        (core-form? head "quote" aliases)))
        acc
        (reduce #(definitions-in ctx %1 %2)
                (if head
                  (let [nm (name head)]
                    (into acc (comp (filter symbol?) (map #(with-meta % nil)))
                          (cond
                            (= nm "declare") (rest node)
                            (.startsWith nm "def") [(second node)])))
                  acc)
                (seq node))))))

(defn- file-definitions
  "Names of defining forms at any depth, skipping comments and quoted data; nil on read
  failure (never evidence to unmap). A form nested past the reader's or this walk's
  stack depth is a read failure too, which is why Throwable is caught."
  [ns source]
  (try
    (binding [*read-eval* false *ns* ns]
      (with-open [reader (java.io.PushbackReader. (io/reader source))]
        ;; A unique object, never a value the file can contain: any sentinel that is
        ;; itself a readable form (a keyword, say) ends the scan early when the file
        ;; happens to contain it, and every definition after it is read as deleted.
        (let [eof (Object.)
              forms (loop [acc []]
                      (let [form (read {:read-cond :allow :eof eof} reader)]
                        (if (identical? eof form) acc (recur (conj acc form)))))
              aliases (into #{} (mapcat core-aliases) forms)
              ;; Whole file, not form by form: a file whose ns form may stop `comment`
              ;; meaning clojure.core's, or that defines its own `comment` anywhere
              ;; (at any depth, before or after its uses), has no bare comment form.
              ;; Order does not matter, so no ordering of forms can unmap a definition.
              shadowed? (or (boolean (some comment-shadowed? forms))
                            (contains? (reduce #(definitions-in {:aliases aliases :bare-comment? false} %1 %2)
                                               #{} forms)
                                       'comment))]
          (reduce #(definitions-in {:aliases aliases :bare-comment? (not shadowed?)} %1 %2)
                  #{} forms))))
    (catch Throwable _
      (binding [*out* *err*]
        (println (str "reload: cannot read source forms for " (ns-name ns) "; unmapping nothing")))
      nil)))

;; INTENT: LOOP-RELOAD-UNMAP-018
(defn unmap-stale!
  "After a successful reload of `ns-sym`, given `before` (its `interned-meta` taken just
  before the reload) and `source` (its file, or nil): every var still interned whose
  metadata map is identical to the one recorded was not defined by the reload.
  - its :file names `source`: keep if the file still defines it, otherwise -> :unmapped
  - unreadable source: keep every candidate, with one stderr warning for the namespace
  - it has another real :file (defined into this namespace by some other file): keep -> :kept
  - no :file (runtime intern, stub, protocol method var): keep, counted in :dynamic
  Entries are {:ns :var :file :line :var <the Var object>}; :var-object is for the
  caller's referrer sweep and is not printable data."
  [ns-sym before ^java.io.File source]
  (let [ns (find-ns ns-sym)
        ;; A file-system call: made only once a var turns out not to be redefined.
        source (delay (some-> source .getCanonicalFile))
        definitions (delay (file-definitions ns @source))]
    (reduce-kv
     (fn [acc sym m]
       (let [v (when ns (.getMapping ^clojure.lang.Namespace ns sym))]
         (if (and (instance? clojure.lang.Var v)
                  (identical? ns (.ns ^clojure.lang.Var v))
                  (identical? m (meta v)))
           (let [entry {:ns ns-sym :var sym :file (:file m) :line (:line m) :var-object v}]
             (cond
                (file-names? (:file m) @source)
                (cond
                  (nil? @definitions) (update acc :kept conj (assoc entry :reason :unreadable-file))
                  (contains? @definitions sym) (update acc :kept conj (assoc entry :reason :still-in-file))
                  :else (do (ns-unmap ns sym) (update acc :unmapped conj entry)))
               (and (string? (:file m)) (not= "NO_SOURCE_PATH" (:file m))) (update acc :kept conj entry)
               :else (update acc :dynamic inc)))
           acc)))
     {:unmapped [] :kept [] :dynamic 0}
     (or before {}))))

;; INTENT: LOOP-RELOAD-UNMAP-018
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
