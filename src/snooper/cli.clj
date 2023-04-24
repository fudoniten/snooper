(ns snooper.cli
  (:require [clojure.core.async :as async :refer [>!! <!!]]
            [clojure.tools.cli :as cli]
            [clojure.set :as set]
            [clojure.string :as str]
            [snooper.core :as snooper]
            [milquetoast.client :as mqtt]
            [fudo-clojure.logging :as log])
  (:gen-class))

(def cli-opts
  [["-v" "--verbose" "Provide verbose output."]
   ["-h" "--help" "Print this message."]

   [nil "--mqtt-host HOSTNAME" "Hostname of MQTT server."]
   [nil "--mqtt-port PORT" "Port on which to connect to the MQTT server."
    :parse-fn #(Integer/parseInt %)]
   [nil "--mqtt-user USER" "User as which to connect to MQTT server."]
   [nil "--mqtt-password-file PASSWD_FILE" "File containing password for MQTT user."]

   [nil "--event-topic EVT_TOPIC" "MQTT topic to which events should be published."
    :multi true
    :update-fn conj]
   [nil "--notification-topic NOTIFY_TOPIC" "Topic to which notifications will be sent."]])

(defn- msg-quit [status msg]
  (println msg)
  (System/exit status))

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: snooper-client [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- parse-opts [args required cli-opts]
  (let [{:keys [options] :as result} (cli/parse-opts args cli-opts)
        missing (set/difference required (-> options (keys) (set)))
        missing-errors (map #(format "missing required parameter: %s" (name %))
                            missing)]
    (update result :errors concat missing-errors)))

(defn -main [& args]
  (let [required-args #{:mqtt-host :mqtt-port :mqtt-user :mqtt-password-file :event-topic :notification-topic}
        {:keys [options _ errors summary]} (parse-opts args required-args cli-opts)]
    (when (:help options) (msg-quit 0 (usage summary)))
    (when (seq errors) (msg-quit 1 (usage summary errors)))
    (let [{:keys [mqtt-host
                  mqtt-port
                  mqtt-user
                  mqtt-password-file
                  notification-topic
                  event-topic
                  verbose]} options
          catch-shutdown (async/chan)
          mqtt-client (mqtt/connect-json! :host mqtt-host
                                          :port mqtt-port
                                          :username mqtt-user
                                          :password (-> mqtt-password-file
                                                        (slurp)
                                                        (str/trim)))
          logger (log/print-logger)]
      (when verbose
        (println (format "launching snooper server to listen on %s and report events on %s"
                         event-topic notification-topic)))
      (snooper/listen! :mqtt-client        mqtt-client
                       :notification-topic notification-topic
                       :event-topics       event-topic
                       :logger             logger
                       :verbose            verbose)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! catch-shutdown true))))
      (<!! catch-shutdown)
      (println "stopping snooper server")
      ;; Stopping the MQTT will stop tattler
      (mqtt/stop! mqtt-client)
      (System/exit 0))))
