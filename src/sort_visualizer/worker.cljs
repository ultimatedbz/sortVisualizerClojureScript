(ns sort-visualizer.worker
  (:require [sort-visualizer.algorithms :as algorithms]
            [sort-visualizer.custom :as custom]))

(defn- handle-message! [event]
  (let [{:keys [id algorithm values code]} (js->clj (.-data event) :keywordize-keys true)]
    (try
      (let [result (if (= "custom" algorithm)
                     (custom/generate-trace code values)
                     (algorithms/generate-trace (keyword algorithm) values))]
        (.postMessage js/self (clj->js {:id id :result result})))
      (catch :default error
        (.postMessage js/self
                      (clj->js {:id id
                                :error (or (ex-message error)
                                           (.-message error)
                                           "The algorithm could not run.")}))))))

(defn init []
  (set! (.-onmessage js/self) handle-message!))
