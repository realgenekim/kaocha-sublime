(ns kaocha-sublime.stale
  "Stale-image checks after a warm reload: a namespace whose declared refers did not
  take, and namespaces that still map a var its home namespace no longer defines.
  Design: docs/intent/stale-refer/stale-refer-design.md.

  The field case: a function moves from a.core to a.render and a.core's ns form gains
  (:require [a.render :refer [f]]). In the warm JVM a.core still interns f, so Clojure
  prints `REJECTED: attempt to replace interned var ...` to *err* and keeps the old var;
  a.core's code calls the old body and the suite passes warm, fails cold."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.parse :as parse])
  (:import (java.io File StringWriter Writer)
           (java.util IdentityHashMap)))

;; ---------------------------------------------------------------- declared refers

(defn- libspec?
  "Clojure's own rule (clojure.core/load-libs): a symbol, or a sequential whose second
  element is a keyword or that has one element, is a libspec; otherwise a prefix list."
  [x]
  (or (symbol? x)
      (and (sequential? x) (or (keyword? (second x)) (nil? (next x))))))

(defn- expand-specs
  "[lib opts-map] for every libspec in `args` of a :require or :use clause, prefix lists
  expanded."
  [args]
  (mapcat (fn [x]
            (cond
              (keyword? x) []                ; :reload, :verbose ... flags
              (libspec? x) (if (symbol? x)
                             [[x {}]]
                             [[(first x) (apply hash-map (rest x))]])
              (sequential? x) (let [[prefix & specs] x]
                                (for [s specs
                                      :let [[lib opts] (if (symbol? s) [s {}] [(first s) (apply hash-map (rest s))])]]
                                  [(symbol (str prefix "." lib)) opts]))
              :else []))
          args))

;; INTENT: LOOP-STALE-REFER-011
(defn declared-refers
  "What an ns form says it refers, as {:lib sym :local sym :home sym} (explicit names)
  and {:lib sym :all true :exclude #{} :rename {}} (:refer :all, :use without :only).
  Handles :require and :use, :refer, :only, :rename, :exclude and prefix lists."
  [ns-form]
  (let [line (:line (meta ns-form))]
    (vec
     (for [clause (drop 2 ns-form)
           :when (and (sequential? clause) (#{:require :use} (first clause)))
           :let [use? (= :use (first clause))]
           [lib opts] (expand-specs (rest clause))
           :let [names (if use? (:only opts) (:refer opts))
                 rename (or (:rename opts) {})]
           entry (cond
                   (or (= :all names) (and use? (nil? names)))
                   [{:lib lib :all true :exclude (set (:exclude opts)) :rename rename :line line}]
                   (sequential? names)
                   (for [home names] {:lib lib :local (get rename home home) :home home :line line})
                   :else [])]
       entry))))

(defonce ^:private decl-cache (atom {}))

(defn- read-ns-form [^File f]
  (try
    (with-open [r (clojure.lang.LineNumberingPushbackReader. (io/reader f))]
      (parse/read-ns-decl r {:read-cond :allow :features #{:clj}}))
    (catch Exception _ nil)))

(defn file-refers
  "`declared-refers` of `file`'s ns form, cached by path, size and mtime: ns forms
  change rarely and this runs for every reloaded namespace on every check."
  [^File f]
  (when (and f (.isFile f))
    (let [k (.getPath f) stamp [(.lastModified f) (.length f)]
          hit (get @decl-cache k)]
      (if (= stamp (:stamp hit))
        (:refers hit)
        (let [refers (some-> (read-ns-form f) declared-refers)]
          (swap! decl-cache assoc k {:stamp stamp :refers refers})
          refers)))))

;; ---------------------------------------------------------------- reload stderr

(def ^:private rejected-re
  #"REJECTED: attempt to replace interned var #'(\S+) with #'(\S+) in (\S+), you must ns-unmap first")

(defn tee-writer
  "A Writer that copies everything to `out` and also keeps it in a buffer
  (`(str buffer)`): Clojure's REJECTED and WARNING lines still reach stderr."
  [^Writer out ^StringWriter buffer]
  (proxy [Writer] []
    (write
      ([x] (if (integer? x)
             (do (.write out (int x)) (.write buffer (int x)))
             (let [s (if (string? x) x (String. ^chars x))] (.write out s) (.write buffer s))))
      ([x off len]
       (if (string? x)
         (do (.write out ^String x (int off) (int len)) (.write buffer ^String x (int off) (int len)))
         (do (.write out ^chars x (int off) (int len)) (.write buffer ^chars x (int off) (int len))))))
    (flush [] (.flush out))
    (close [] nil)))

(defn reload-lines
  "Clojure's own reload messages in captured stderr text: :rejected entries
  {:ns :kept :wanted :line} and :warnings (the `WARNING: ... already refers to` lines)."
  [text]
  (let [lines (str/split-lines (or text ""))]
    {:rejected (vec (for [l lines :let [[_ kept wanted ns] (re-find rejected-re l)] :when kept]
                      {:ns ns :kept kept :wanted wanted :line (str/trim l)}))
     :warnings (vec (for [l lines :when (re-find #"^WARNING: \S+ already refers to:" l)] (str/trim l)))}))

;; ---------------------------------------------------------------- checks

(defn- var-name [^clojure.lang.Var v]
  (when v (str "#'" (ns-name (.ns v)) "/" (.sym v))))

(defn- public-var [lib sym]
  (when-let [^clojure.lang.Namespace ns (find-ns lib)]
    (let [v (.findInternedVar ns sym)]
      (when (and v (.isPublic ^clojure.lang.Var v)) v))))

;; INTENT: LOOP-STALE-REFER-011
(defn refer-mismatches
  "Check 1: for each reloaded namespace, every declared refer whose actual mapping is
  not the var its home namespace now exports. `files` maps namespace -> File;
  `stale-own` is the set of Var objects the reload found stale but kept (their :file
  is another file). Entries {:ns :sym :lib :home :class :actual :expected :line}:
  :class \"refused\" when the namespace interned the name (Clojure REJECTED the
  refer) and the file no longer defines it: the reload unmapped it, or kept it as
  stale; its compiled code still calls that old var. \"old-var\" when it maps another
  var, \"missing\" when the home no longer exports the name (a cold JVM throws
  IllegalAccessError). A local definition the file still has is not stale: a cold JVM
  refers, then the definition replaces the refer, which is where the warm JVM ends too."
  [namespaces files stale-own]
  (vec
   (for [n namespaces
         :let [^clojure.lang.Namespace the-ns (find-ns n)]
         :when the-ns
         spec (file-refers (get files n))
         [local home] (if (:all spec)
                        (when-let [lib (find-ns (:lib spec))]
                          (for [[sym v] (ns-publics lib)
                                :when (not (contains? (:exclude spec) sym))
                                :when (identical? lib (.ns ^clojure.lang.Var v))]
                            [(get (:rename spec) sym sym) sym]))
                        [[(:local spec) (:home spec)]])
         :let [expected (public-var (:lib spec) home)
               actual (.getMapping the-ns local)]
         :let [own? (and (var? actual) (identical? the-ns (.ns ^clojure.lang.Var actual)))]
         :when (not (identical? expected actual))
         :when (not (and own? (not (contains? stale-own actual))))
         ;; :refer :all only reports names a cold JVM would refer: present now.
         :when (or expected (not (:all spec)))
         :when (or (nil? actual) (var? actual))]
     {:ns n :sym local :lib (:lib spec) :home home :line (:line spec)
      :class (cond (nil? expected) "missing"
                   (or (nil? actual) own?) "refused"
                   :else "old-var")
      :actual (if (var? actual) (var-name actual) (some-> actual str))
      :expected (var-name expected)})))

;; INTENT: LOOP-STALE-REFER-011
(defn stale-referrers
  "Check 3: every namespace mapping (other than the var's own home) whose value is one
  of `stale-vars` (Var objects the reload unmapped). {:ns :sym :refers :home}."
  [stale-vars]
  (if (empty? stale-vars)
    []
    (let [index (IdentityHashMap.)]
      (doseq [v stale-vars] (.put index v true))
      ;; reduce-kv over each mapping table: no seq or entry allocation for the
      ;; ~10^5 mappings of a large image, only an identity lookup each.
      (persistent!
       (reduce (fn [acc ^clojure.lang.Namespace ns]
                 (reduce-kv (fn [acc sym v]
                              (if (and (.containsKey index v)
                                       (not (identical? ns (.ns ^clojure.lang.Var v))))
                                (conj! acc {:ns (ns-name ns) :sym sym :refers (var-name v)
                                            :home (ns-name (.ns ^clojure.lang.Var v))})
                                acc))
                            acc (.getMappings ns)))
               (transient []) (all-ns))))))

(defn var-label [v] (var-name v))
