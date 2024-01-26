(ns confr.cli.commands
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [confr.core :as confr]
            [malli.error :as me]
            [lambdaisland.deep-diff2 :as dd]))

;; Helper-functions

(defn- to-json [env]
  (json/generate-string env))

(defn- formatter [{:keys [format]}]
  (case format
    "edn" pr-str
    "json" to-json
    "env-var" (comp confr/export-env-vars (partial sort-by first) confr/env-vars)
    pr-str))

(defn- load-env [env {:keys [no-resolve] :as opts}]
  (cond-> (confr/load-env env opts)
    (not no-resolve) (confr/resolve-vals opts)))

(defn- find-envs [{:keys [conf-dir]}]
  (->> (io/file (str conf-dir "/environments"))
       (file-seq)
       (map (memfn getName))
       (filter #(str/ends-with? % ".edn"))
       (map #(str/replace % #".edn$" ""))))

;; Commands

(defn validate [{{:keys [env] :as opts} :opts}]
  (let [formatter (formatter opts)
        schema (confr/load-schema opts)
        envs (if env [env] (find-envs opts))
        results (->> envs
                     (map (juxt identity #(confr/validate schema (load-env % opts))))
                     (filter second))]
    (when results
      (doseq [[env errors] results]
        (println (formatter {:environment env
                             :errors (me/humanize errors)})))
      (System/exit 1))))

(defn generate [{:keys [opts]}]
  (let [formatter (formatter opts)]
    (-> (confr/load-schema opts)
        (confr/generate)
        (formatter)
        (println))))

(defn diff [{{:keys [env format] :as opts} :opts}]
  (let [printer (case format
                  ;; FIXME: This doesn't work well in all diffs
                  "json" (comp println json/generate-string)
                  dd/pretty-print)]
    (printer (dd/diff (load-env (first env) opts)
                      (load-env (second env) opts)))))

(defn export [{{:keys [env no-validate] :as opts} :opts}]
  (let [env (load-env env opts)
        schema (confr/load-schema opts)
        formatter (formatter opts)
        errors (and (not no-validate) (confr/validate schema env))]
    (when errors
      (binding [*out* *err*]
        (println "Invalid environment")
        (println (pr-str (me/humanize errors))))
      (System/exit 1))
    (println (formatter env))))
