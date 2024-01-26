(ns confr.cli.commands
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [confr.core :as confr]
            [malli.error :as me]
            [lambdaisland.deep-diff2 :as dd]
            [org.httpkit.server :as hks]))

;; Helper-functions

(defn- to-json [env]
  (json/generate-string env {:pretty true}))

(defn- pretty-printer [{:keys [format]}]
  (case format
    "edn" (fn [x]
            (dd/pretty-print x (dd/printer {:print-color (System/console)})))
    "json" (comp println to-json)
    "env-var" (comp println confr/export-env-vars (partial sort-by first) confr/env-vars)
    (comp println pr-str)))

(defmacro fail-with-message [msg-fn & body]
  `(try ~@body
        (catch Exception e#
          (binding [*out* *err*]
            (println (~msg-fn) (ex-message e#))
            (System/exit 1)))))

(defn- load-env [env {:keys [no-resolve] :as opts}]
  (fail-with-message
   #(format "Failed to load env %s" env)
   (cond-> (confr/load-env env opts)
     (not no-resolve) (confr/resolve-vals opts))))

(defn- load-schema [opts]
  (fail-with-message
   (constantly "Failed to load schema")
   (confr/load-schema opts)))

(defn- find-envs [{:keys [conf-dir]}]
  (->> (io/file (str conf-dir "/environments"))
       (file-seq)
       (map (memfn getName))
       (filter #(str/ends-with? % ".edn"))
       (map #(str/replace % #".edn$" ""))))

;; Commands

(defn validate [{{:keys [env] :as opts} :opts}]
  (let [printer (pretty-printer opts)
        schema (load-schema opts)
        envs (if env [env] (find-envs opts))
        results (->> envs
                     (map (juxt identity #(confr/validate schema (load-env % opts))))
                     (filter second))]
    (when results
      (doseq [[env errors] results]
        (printer {:environment env
                  :errors (me/humanize errors)}))
      (System/exit 1))))

(defn generate [{:keys [opts]}]
  (let [printer (pretty-printer opts)]
    (-> (load-schema opts)
        (confr/generate)
        (printer))))

(defn diff [{{:keys [env] :as opts} :opts}]
  (let [printer (pretty-printer opts) #_(case format
                  ;; FIXME: This doesn't work well in all diffs
                  "json" (comp println json/generate-string)
                  dd/pretty-print)]
    (printer (dd/diff (load-env (first env) opts)
                      (load-env (second env) opts)))))

(defn export [{{:keys [env no-validate no-resolve] :as opts} :opts}]
  (let [env (load-env env opts)
        schema (load-schema opts)
        printer (pretty-printer opts)
        errors (and (not no-validate)
                    (not no-resolve)
                    (confr/validate schema env))]
    (when errors
      (binding [*out* *err*]
        (println "Invalid environment")
        (println (pr-str (me/humanize errors))))
      (System/exit 1))
    (printer env)))

(defn serve [{{:keys [env port ip once no-validate no-resolve format] :as opts} :opts}]
  (let [env (load-env env opts)
        schema (load-schema opts)
        formatter (case format
                    "edn" pr-str
                    "json" to-json)
        errors (and (not no-validate)
                    (not no-resolve)
                    (confr/validate schema env))
        shutdown (promise)
        server (atom nil)
        handler (fn [_]
                  (when once
                    (deliver shutdown @server))
                  {:status 200
                   :headers {"Content-type" (str "application/" format)}
                   :body (formatter (confr/resolve-vals env opts))})]
    (when errors
      (binding [*out* *err*]
        (println "Invalid environment")
        (println (pr-str (me/humanize errors))))
      (System/exit 1))
    (reset! server (hks/run-server handler {:port port
                                            :ip ip
                                            :legacy-return-value? true}))
    (@shutdown)))
