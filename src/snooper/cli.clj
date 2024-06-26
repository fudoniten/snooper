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

   [nil "--incoming-mqtt-host HOSTNAME" "Hostname of MQTT server on which to listen for events."]
   [nil "--incoming-mqtt-port PORT" "Port on which to connect to the incoming MQTT server."
    :parse-fn #(Integer/parseInt %)]
   [nil "--incoming-mqtt-user USER" "User as which to connect to MQTT server."]
   [nil "--incoming-mqtt-password-file PASSWD_FILE" "File containing password for MQTT user."]

   [nil "--outgoing-mqtt-host HOSTNAME" "Hostname of MQTT server to which notifications will be sent."]
   [nil "--outgoing-mqtt-port PORT" "Port on which to connect to the outgoing MQTT server."
    :parse-fn #(Integer/parseInt %)]
   [nil "--outgoing-mqtt-user USER" "User as which to connect to MQTT server."]
   [nil "--outgoing-mqtt-password-file PASSWD_FILE" "File containing password for MQTT user."]

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
  (let [required-args #{:incoming-mqtt-host
                        :incoming-mqtt-port
                        :incoming-mqtt-user
                        :incoming-mqtt-password-file
                        :outgoing-mqtt-host
                        :outgoing-mqtt-port
                        :outgoing-mqtt-user
                        :outgoing-mqtt-password-file
                        :event-topic
                        :notification-topic}
        {:keys [options _ errors summary]} (parse-opts args required-args cli-opts)]
    (when (:help options) (msg-quit 0 (usage summary)))
    (when (seq errors) (msg-quit 1 (usage summary errors)))
    (let [{:keys [incoming-mqtt-host
                  incoming-mqtt-port
                  incoming-mqtt-user
                  incoming-mqtt-password-file
                  outgoing-mqtt-host
                  outgoing-mqtt-port
                  outgoing-mqtt-user
                  outgoing-mqtt-password-file
                  notification-topic
                  event-topic
                  verbose]} options
          catch-shutdown (async/chan)
          incoming-client (mqtt/connect-json! :host incoming-mqtt-host
                                              :port incoming-mqtt-port
                                              :username incoming-mqtt-user
                                              :password (-> incoming-mqtt-password-file
                                                            (slurp)
                                                            (str/trim)))
          outgoing-client (mqtt/connect-json! :host outgoing-mqtt-host
                                              :port outgoing-mqtt-port
                                              :username outgoing-mqtt-user
                                              :password (-> outgoing-mqtt-password-file
                                                            (slurp)
                                                            (str/trim)))
          logger (log/print-logger)]
      (when verbose
        (println (format "launching snooper server to listen on %s and report events on %s"
                         event-topic notification-topic)))
      (snooper/listen! :incoming-mqtt-client incoming-client
                       :outgoing-mqtt-client outgoing-client
                       :notification-topic   notification-topic
                       :event-topics         event-topic
                       :logger               logger
                       :verbose              verbose)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! catch-shutdown true))))
      (<!! catch-shutdown)
      (println "stopping snooper server")
      ;; Stopping the MQTT will stop tattler
      (mqtt/stop! incoming-client)
      (mqtt/stop! outgoing-client)
      (System/exit 0))))
