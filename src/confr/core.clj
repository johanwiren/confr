(ns confr.core
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.util :as mu]
            [com.grzm.awyeah.client.api :as aws]
            [taoensso.timbre :as log]))

(log/set-level! :fatal)

(defn load-schema [{:keys [conf-dir]}]
  (-> (slurp (format "%s/schema.edn" conf-dir))
      (edn/read-string)
      (mu/closed-schema)))

(declare load-env)

(defn resolve-includes [{:confr/keys [include] :as env}
                        {:keys [loaded-includes] :as opts
                         :or {loaded-includes #{}}}]
  (when-let [circ (some loaded-includes include)]
    (binding [*out* *err*]
      (println "Circular dependency detected while loading" circ))
    (System/exit 1))
  (let [opts (update opts :loaded-includes (fnil into #{}) include)]
    (->> (conj (mapv #(load-env % opts) include) (dissoc env :confr/include))
         (apply merge))))

(defn load-env [env {:keys [conf-dir] :as opts}]
  (resolve-includes (edn/read-string (slurp (format "%s/environments/%s.edn" conf-dir env))) opts))

(declare resolve-vals)

(defmulti resolve-val (fn [x _]
                       (when (map? x)
                         (:confr/resolver x))))

(defmethod resolve-val :file [{:keys [file]} {:keys [conf-dir]}]
  (slurp (format "%s/environments/%s" conf-dir file)))

(defmethod resolve-val :json-file [{:keys [json-file]} {:keys [conf-dir]}]
  (json/parse-string (slurp (format "%s/environments/%s" conf-dir json-file)) true))

(defmethod resolve-val :aws.secretsmanager/secret-string [{:keys [secret-id]} _]
  (let [sm (aws/client {:api :secretsmanager})]
    (-> (aws/invoke sm {:op :GetSecretValue
                        :request {:SecretId secret-id}})
        (:SecretString))))

(defmethod resolve-val :default [x opts]
  (cond
    (map? x)
    (resolve-vals x opts)

    (sequential? x)
    (map #(resolve-vals % opts) x)

    :else
    x))

(defn resolve-vals [env opts]
  (update-vals env #(resolve-val % opts)))

(defn validate [schema env]
  (m/explain schema env))

(defn generate [schema]
  (mg/generate schema))

(defn to-env [k]
  (cond
    (string? k) k
    :else
    (let [ns (namespace k)
          k (cond->> (name k)
              ns (str "_"))]
      (-> (str ns k) str/upper-case (str/replace #"[^A-Z0-9]" "_")))))

(defn flat
  ([env]
   (flat env nil))
  ([env path]
   (cond
     (map? env)
     (mapcat #(flat % path) env)

     (map-entry? env)
     (flat (val env) (cond->> (to-env (key env))
                       path (str path "__")))

     (sequential? env)
     (flatten (map-indexed (fn [i v]
                             (let [path (cond->> (str i)
                                          path (str path "__"))]
                               (flat v path)))
                           env))

     (keyword? env)
     [path (name env)]

     :else [path (if (some? env) (pr-str env) "")])))

(defn env-vars [env]
  (partition 2 (flat env)))

(defn export-env-vars [env-vars]
  (->> env-vars
       (map (fn [[k v]]
              (format "export %s=%s" k v)))
       (str/join "\n")))
