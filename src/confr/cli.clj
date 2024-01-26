(ns confr.cli
  (:require [babashka.cli :as cli]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [confr.cli.commands :as cmds]))

(defn error-fn [{:keys [spec type cause msg option] :as data}]
  (binding [*out* *err*]
    (if (= :org.babashka/cli type)
      (case cause
        :require
        (println
         (format "Missing required argument:\n%s"
                 (cli/format-opts {:spec (select-keys spec [option])})))
        (println msg))
      (throw (ex-info msg data))))
  (System/exit 1) )

(defn with-formats
  ([cmd]
   (with-formats cmd ["edn" "json" "env-var"]))
  ([cmd formats]
   (-> cmd
       (update :spec assoc :format {:default (name (first formats))
                                    :desc (format "Output format <%s>" (str/join ", " formats))})
       (update :validate assoc :format (set formats)))))

(defn with-defaults [cmd]
  (-> cmd
      (update :spec assoc :conf-dir {:default "."
                                     :desc "Configuration directory"})
      (update :validate assoc :conf-dir (fn [conf-dir] (.isDirectory (io/file conf-dir))))))

(defn with-no-resolve [cmd]
  (-> cmd
      (update :spec assoc :no-resolve {:default false
                                       :coerce boolean
                                       :desc "Do not resolve external values before processing"})))

(defn with-help [cmd]
  (let [cmd (update cmd :spec assoc :help {:default false
                                           :alias :h
                                           :coerce boolean
                                           :desc "Displays this help message"})
        help-fn (fn [_]
                  (println (format "Usage: confr %s %s [opts]"
                                   (apply str (:cmds cmd))
                                   (str/join " " (map (comp #(format "<%s>" %) name) (:args->opts cmd)))))
                  (println)
                  (println (cli/format-table
                            {:rows (into [["Alias  " "Option  " "Default value  " "Description"]]
                                         (cli/opts->table (-> cmd
                                                              (update :spec #(apply dissoc % (:args->opts cmd)))
                                                              (dissoc :args->opts))))
                             :indent 4}))
                  (println)
                  (System/exit 1))]
    (-> cmd
        (update :fn (fn [f]
                      (fn [{{:keys [help]} :opts :as cmd'}]
                        (if help
                          (help-fn nil)
                          (f cmd')))))
        (assoc :error-fn help-fn))))

(def dispatch-table
  [(-> {:cmds ["validate"]
        :fn cmds/validate
        :args->opts [:env]}
       (with-defaults)
       (with-no-resolve)
       (with-formats ["edn" "json"])
       (with-help))
   (-> {:cmds ["generate"]
        :fn cmds/generate}
       (with-defaults)
       (with-formats)
       (with-help))
   (-> {:cmds ["diff"]
        :fn cmds/diff
        :args->opts [:env :env]
        :spec {:env {:coerce []
                     :require true}}
        :validate {:env #(= 2 (count %))}}
       (with-defaults)
       (with-no-resolve)
       (with-help))
   (-> {:cmds ["export"]
        :fn cmds/export
        :args->opts [:env]
        :spec {:no-validate {:default false
                             :coerce boolean
                             :desc "Skip validation"}}
        :validate {:env not-empty}
        :require [:env]}
       (with-defaults)
       (with-no-resolve)
       (with-formats)
       (with-help))])

(defn -main [& args]
  (cli/dispatch dispatch-table args))
