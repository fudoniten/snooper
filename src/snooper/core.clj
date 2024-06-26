(ns snooper.core
  (:require [clojure.core.async :refer [go-loop alts!]]
            [clojure.pprint :refer [pprint]]
            [fudo-clojure.logging :as log]
            [milquetoast.client :as mqtt]
            [malli.core :as t]))

(def critical-objects [:person :bear])
(def normal-objects [:cat :dog])

(defn- verbose-pthru [verbose obj]
  (when verbose (pprint obj))
  obj)

(defn- objects-criticality [objs]
  (cond (some (partial contains? objs) critical-objects) 9
        (some (partial contains? objs) normal-objects)   5
        :else                                            1))

(defn- objects-probability [objs]
  (let [prob (apply max (vals objs))]
    (cond (<= 0.4 prob 0.6) :possibly
          (<= 0.6 prob 0.8) :likely
          (<= 0.8 prob 0.9) :probably
          (<= 0.9 prob 1.0) :definitely
          :else             nil)))

(defn- sized-string [min max]
  (t/schema [:string {:min min :max max}]))

(def Notification
  (t/schema [:map
             [:summary (sized-string 1 80)]
             [:body    (sized-string 1 256)]
             [:urgency [:and :int [:>= 0] [:<= 10]]]]))

(def MotionEvent
  (t/schema [:map
             [:payload
              [:map
               [:detection
                [:map
                 [:location string?]
                 [:objects [:map-of keyword? number?]]
                 [:detection-url string?]]]]]
             [:topic string?]]))

(defn- add-a-or-an [obj]
  (let [first-char (first (name obj))]
    (if (some #(= first-char %) [\a \e \i \o \u])
      (format "an %s" (name obj))
      (format "a %s" (name obj)))))

(defn- objects-string
  ([obj0] (add-a-or-an obj0))
  ([obj0 obj1] (format "%s and %s" (add-a-or-an obj0) (objects-string obj1)))
  ([obj0 obj1 & objs] (format "%s, %s" (add-a-or-an obj0) (apply objects-string (concat [obj1] objs)))))

(defmulti event-summary :probability)

(defmethod event-summary :possibly [{:keys [description location]}]
  (format "There could possibly be %s at the %s" description location))
(defmethod event-summary :likely [{:keys [description location]}]
  (format "There might be %s at the %s" description location))
(defmethod event-summary :probably [{:keys [description location]}]
  (format "There's probably %s at the %s" description location))
(defmethod event-summary :definitely [{:keys [description location]}]
  (format "There's %s at the %s" description location))
(defmethod event-summary :default [_]
  nil)

(defmulti translate-event :type)

(defmethod translate-event "detection-event"
  [{{:keys [objects location detection-url]} :detection}]
  (let [criticality (objects-criticality objects)
        probability (objects-probability objects)
        description (apply objects-string (keys objects))]
    {:summary (event-summary {:criticality criticality
                              :probability probability
                              :location    location
                              :description description})
     :body    detection-url
     :urgency criticality}))

(defn listen!
  [& {incoming-client    :incoming-mqtt-client
      outgoing-client    :outgoing-mqtt-client
      notification-topic :notification-topic
      event-topics       :event-topics
      logger             :logger
      verbose            :verbose}]
  (let [incoming (map (partial mqtt/subscribe! incoming-client) event-topics)
        valid-evt? (t/validator MotionEvent)]
    (go-loop [[evts _] (alts! incoming)]
      (let [evt (first evts)]
        (when verbose (pprint evt))
        (cond (nil? evt)       (log/info! logger "stopping")
              (valid-evt? evt) (do (log/info! logger (format "received motion event id %s from %s"
                                                             (:id evt)
                                                             (:topic evt)))
                                   (mqtt/send! outgoing-client notification-topic
                                               (verbose-pthru verbose (translate-event (:payload evt))))
                                   (recur (alts! incoming)))
              :else            (do (log/error! logger (format "invalid motion event: %s" evt))
                                   (recur (alts! incoming))))))))
