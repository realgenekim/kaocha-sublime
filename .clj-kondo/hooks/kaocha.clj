(ns hooks.kaocha (:require [clj-kondo.hooks-api :as api]))
(defn defplugin [{:keys [node]}]
  (let [[_ id & hooks] (:children node)]
    {:node (api/list-node
             (cons (api/token-node 'do)
                   (for [hook hooks :when (= :list (api/tag hook))
                         :let [[name & body] (:children hook)]]
                     (api/list-node
                       (concat [(api/token-node 'defn)
                                (api/token-node (symbol (str (clojure.core/name (api/sexpr id)) "-" (api/sexpr name) "-hook")))]
                               body)))))}))
