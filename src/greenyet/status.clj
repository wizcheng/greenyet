(ns greenyet.status
  (:require [cheshire.core :as j]
            [greenyet.parse :as parse]
            [clj-http.client :as http]
            [greenyet.config :as config]
            ))

(defn- status-color-from-components [components]
  (let [colors (map :color components)
        status-color (cond
                       (or (some #(= % :red) colors) (empty? colors)) :red
                       (some #(= % :yellow) colors) :yellow
                       :else :green)]
    [status-color nil]))

(defn- overall-status-color [json config components]
  (or (parse/color json config)
      (status-color-from-components components)))

(defn- status-from-json [json config]
  (let [[components components-error] (parse/components json config)
        [color color-error] (overall-status-color json config components)
        [package-version package-version-error] (parse/package-version json config)
        [message message-error] (parse/message json config)]
    {:color color
     :message (vec (remove nil? (flatten [color-error
                                          package-version-error
                                          components-error
                                          message-error
                                          message])))
     :package-version package-version
     :components components}))

(defn- application-status [body config]
  (if (or (:color config)
          (:components config))
    (let [json (j/parse-string body true)]
      (status-from-json json config))
    {:color :green
     :message "OK"}))

(defn- http-status-code-trumps-app-status [http-status-code status]
  (if (>= http-status-code 300)
    (assoc status :color :red)
    status))

(defn- message-for-http-response [response]
  (let [body (:body response)]
    (if-not (empty? body)
      (format "Status %s: %s" (:status response) body)
      (format "Status %s" (:status response)))))

(defn- http-get [status-url timeout-in-ms callback-response callback-error]
  (http/get status-url
            {:headers {"Accept" "application/json"}
             :follow-redirects false
             :user-agent "greenyet"
             :socket-timeout timeout-in-ms
             :conn-timeout timeout-in-ms
             :insecure? true
             :async? true
             :throw-exceptions false
             :keystore config/key-store
             :keystore-pass config/key-store-pass
             :trust-store config/trust-store
             :trust-store-pass config/trust-store-pass
             }
              callback-response callback-error))

(defn- identify-status [response timeout-in-ms config]
  (let [known-status-codes (set (or (:known-status-codes config)
                                    [200]))]
    (try
      (cond
        (:error response) {:color :red
                           :message (format "greenyet: %s" (.getMessage (:error response)))}
        (contains? known-status-codes (:status (:data response))) (->> config
                                                               (application-status (:body (:data response)))
                                                               (http-status-code-trumps-app-status (:status (:data response))))
        :else {:color :red
               :message (message-for-http-response (:data response))})
      (catch Exception e
        {:color :red
         :message (.getMessage e)}))))

(defn fetch-status [{:keys [status-url config]} timeout-in-ms callback]
  (http-get status-url
            timeout-in-ms
            (fn [response]
              (callback (identify-status {:data response} timeout-in-ms config)))
            (fn [response]
              (callback (identify-status {:error response, :data (:data response)} timeout-in-ms config)))
            ))
