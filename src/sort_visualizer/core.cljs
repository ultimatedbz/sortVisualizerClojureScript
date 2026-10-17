(ns sort-visualizer.core
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [sort-visualizer.catalog :as catalog]
            [sort-visualizer.references :as references]
            [sort-visualizer.custom :as custom]
            [sort-visualizer.playback :as playback]))

(defn read-setting! [key fallback]
  (try (or (.getItem js/localStorage key) fallback) (catch :default _ fallback)))
(defn save-setting! [key value]
  (try (.setItem js/localStorage key value) (catch :default _ nil)))
(defn read-code! []
  (let [code (read-setting! "sort-code" custom/default-code)]
    (if (= code custom/legacy-default-code) custom/default-code code)))
(defonce state (r/atom {:algorithm :quick :count 48 :distribution "random"
                       :values (playback/make-values 48 "random" (rand-int 2147483647))
                       :frames nil :index 0 :playing? false :generating? false :speed 80
                       :request-id 0 :clock nil
                       :sound? (= "true" (read-setting! "sort-sound" "false")) :tab :overview
                       :code (read-code!) :language "ClojureScript"
                       :error nil :result-status nil :result-message nil :copied? false}))
;; These handles belong to browser resources; sorting and playback use plain values.
(defonce worker (atom nil))
(defonce worker-timeout (atom nil))
(defonce audio-context (atom nil))
(defonce root (atom nil))
(defonce ticker (atom nil))
(defn by-id [id] (first (filter #(= id (:id %)) catalog/algorithms)))
(defn current-algorithm [app-state]
  (or (by-id (:algorithm app-state))
      {:name "Custom sort" :family "Your algorithm"
       :summary "An algorithm written by you, running in the ClojureScript playground."}))
(defn cancel-worker! []
  (when @worker (.terminate @worker) (reset! worker nil))
  (when @worker-timeout (js/clearTimeout @worker-timeout) (reset! worker-timeout nil)))
(defn clear-trace! []
  (cancel-worker!)
  (swap! state playback/clear-trace))
(defn regenerate!
  ([] (regenerate! {}))
  ([settings]
   (cancel-worker!)
   (swap! state playback/regenerate (rand-int 2147483647) settings)))
(defn update-code! [code]
  (when (= :custom (:algorithm @state)) (cancel-worker!))
  (swap! state playback/update-code code)
  (save-setting! "sort-code" code))
(defn select-algorithm! [id]
  (cancel-worker!)
  (swap! state playback/select-algorithm id (rand-int 2147483647))
  (.replaceState js/history nil "" (str "#/" (name id))))
(defn enable-audio! []
  (try
    (when-not @audio-context
      (when-let [ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
        (reset! audio-context (new ctor))))
    (when @audio-context (.resume @audio-context))
    (catch :default _ nil)))
(defn tone! [f]
  (when (and (:sound? @state) @audio-context (seq (:active f)))
    (try
      (let [ctx @audio-context osc (.createOscillator ctx) gain (.createGain ctx)
            value (get (:values f) (first (:active f)) 1)
            maximum (apply max 1 (:values f)) t (.-currentTime ctx)]
        (set! (.. osc -frequency -value) (+ 180 (* 650 (/ value maximum))))
        (set! (.. gain -gain -value) 0.025)
        (.exponentialRampToValueAtTime (.-gain gain) 0.001 (+ t 0.045))
        (.connect osc gain) (.connect gain (.-destination ctx))
        (.start osc t) (.stop osc (+ t 0.05)))
      (catch :default _ nil))))
(defn request-trace! [mode]
  (cancel-worker!)
  (let [prepared (swap! state playback/prepare)
        id (:request-id prepared)]
    (try
      (let [w (js/Worker. "worker/worker.js")]
        (reset! worker w)
        (set! (.-onmessage w)
              (fn [event]
                (let [data (js->clj (.-data event) :keywordize-keys true)]
                  (when (and (= id (:id data)) (playback/pending? @state id))
                    (cancel-worker!)
                    (if-let [error (:error data)]
                      (swap! state playback/reject-trace id error)
                      (swap! state playback/accept-trace id (:result data) mode (.now js/performance)))))))
        (set! (.-onerror w)
              (fn [_]
                (when (playback/pending? @state id)
                  (cancel-worker!)
                  (swap! state playback/reject-trace id "The algorithm could not run. Try again or check your code."))))
        (reset! worker-timeout
                (js/setTimeout
                 (fn []
                   (when (playback/pending? @state id)
                     (cancel-worker!)
                     (swap! state playback/reject-trace id "Execution stopped after 8 seconds. Check for an infinite loop or reduce the input size.")))
                 8000))
        (.postMessage w (clj->js {:id id :algorithm (name (:algorithm prepared))
                                :values (:values prepared) :code (:code prepared)})))
      (catch :default _
        (cancel-worker!)
        (swap! state playback/reject-trace id "The sorting worker could not start. Reload the page and try again.")))))
(defn play! []
  (when (:sound? @state) (enable-audio!))
  (cond
    (:playing? @state) (swap! state playback/pause)
    (:generating? @state) nil
    (seq (:frames @state)) (swap! state playback/play (.now js/performance))
    :else (request-trace! :play)))
(defn step! []
  (when-not (:generating? @state)
    (if (seq (:frames @state))
      (swap! state playback/step)
      (request-trace! :step))))
(defn restart! [] (swap! state playback/restart))
(defn tick! []
  (let [before @state]
    (when (:playing? before)
      (let [after (swap! state playback/tick (.now js/performance))]
        (when (not= (:index before) (:index after))
          (tone! (playback/current-frame after)))))))

(defn icon [kind]
  [:svg {:width 18 :height 18 :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width 1.8 :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden true}
   (case kind
     :play [:path {:d "m8 5 11 7-11 7Z" :fill "currentColor" :stroke "none"}]
     :pause [:g [:path {:d "M8 5v14M16 5v14" :stroke-width 4}]]
     :step [:g [:path {:d "m5 5 10 7-10 7Z"}] [:path {:d "M19 5v14"}]]
     :reset [:g [:path {:d "M3 10a9 9 0 1 1 2 8M3 4v6h6"}]]
     :shuffle [:g [:path {:d "M3 5h3l12 14h3M3 19h3L18 5h3M17 2l4 3-4 3M17 16l4 3-4 3"}]]
     :sound [:g [:path {:d "M11 4 5 9H2v6h3l6 5ZM15 8a6 6 0 0 1 0 8M18 4a11 11 0 0 1 0 16"}]]
     :mute [:g [:path {:d "M11 4 5 9H2v6h3l6 5ZM16 9l6 6M22 9l-6 6"}]]
     :arrow [:path {:d "M5 19 19 5M5 5h14v14"}]
     :code [:g [:path {:d "m7 6-6 6 6 6m10-12 6 6-6 6M14 3l-4 18"}]]
     :copy [:g [:rect {:x 8 :y 8 :width 12 :height 13 :rx 2}] [:path {:d "M16 8V3H3v13h5"}]]
     [:circle {:cx 12 :cy 12 :r 8}])])
(defn logo [] [:div.logo-mark [:i] [:i] [:i] [:i]])
(def groups [["THE ESSENTIALS" [:quick :merge :heap]] ["STEP BY STEP" [:bubble :selection :insertion]] ["EXPLORE MORE" [:gnome :shaker :odd-even :pancake :bitonic :radix :shell :comb :bogo]]])
(defn sidebar []
  [:aside.sidebar {:aria-label "Algorithms"}
   [:div.sidebar-heading [:span "ALGORITHMS"] [:span.algorithm-total "15"]]
   [:nav.algorithm-list
    (for [[title ids] groups]
      ^{:key title} [:div.algorithm-group
                     [:h3 title]
                     (for [id ids :let [a (by-id id) selected? (= id (:algorithm @state))]]
                       ^{:key id} [:button.algorithm-option {:class (when selected? "selected") :aria-pressed selected? :on-click #(select-algorithm! id)}
                                  [:span (:name a)] [:span.algorithm-hint (if selected? "↗" (if (#{:quick :merge :heap} id) "n log n" ""))]])])]
   [:button.custom-nav {:class (when (= :custom (:algorithm @state)) "selected") :on-click #(do (select-algorithm! :custom) (swap! state assoc :tab :playground))} [icon :code] "Write your own" [:span "↗"]]
   [:div.sidebar-note [:span.status-dot] "All sorting runs in your browser."]])
(defn visualization []
  (let [f (playback/current-frame @state) values (:values f) active (set (:active f)) sorted (set (:sorted f))
        n (count values) max-value (apply max 1 values) complete? (and (seq (:frames @state)) (= (:index @state) (dec (count (:frames @state)))))]
    [:div.visualization
     [:div.graph-top [:span "ARRAY VIEW"] [:div.legend [:span [:i.dot.unsorted] "Unsorted"] [:span [:i.dot.active] "Active"] [:span [:i.dot.sorted] "Sorted"]]]
     [:div.chart-wrap
      [:div.chart-grid [:span "100%"] [:span "75%"] [:span "50%"] [:span "25%"] [:span "0"]]
      [:svg.bar-chart {:viewBox "0 0 1000 310" :preserveAspectRatio "none" :role "img" :aria-label (str "Array visualization: " (str/join ", " values))}
       (for [y [5 80 155 230 305]] ^{:key y} [:line {:x1 0 :x2 1000 :y1 y :y2 y :stroke "#e6ebe3" :stroke-dasharray "3 5"}])
       (for [[i value] (map-indexed vector values)
             :let [height (* 286 (/ value max-value)) width (/ 1000 n)]]
         ^{:key i} [:rect {:x (+ (* i width) 1.5) :y (- 305 height) :width (max 1 (- width 3)) :height (max 2 height) :rx 2
                          :class (cond (contains? active i) "bar active" (contains? sorted i) "bar sorted" :else "bar")}
                    [:title (str "Index " i ": " value)]])]
      [:div.axis-labels [:span "0"] [:span "ARRAY INDEX"] [:span (dec n)]]]
     [:div.trace-caption [:span.status-dot {:class (when (:playing? @state) "pulse")}] [:span (cond (:generating? @state) "Preparing the animation…" (and complete? (:result-message @state)) (:result-message @state) :else (:note f))]
      [:span.frame-counter (if (:frames @state) (str (inc (:index @state)) " / " (count (:frames @state))) "Ready when you are")]]]))
(defn controls []
  (let [busy? (:generating? @state) end? (and (seq (:frames @state)) (= (:index @state) (dec (count (:frames @state)))))]
    [:div.controls
     [:div.play-controls
      [:button.primary-button {:on-click play! :disabled busy?} [icon (if (:playing? @state) :pause :play)] (cond busy? "Preparing…" (:playing? @state) "Pause" end? "Replay sort" (pos? (:index @state)) "Resume" :else "Start sort")]
      [:button.icon-button {:on-click step! :disabled busy? :title "Step forward" :aria-label "Step forward"} [icon :step]]
      [:button.icon-button {:on-click restart! :disabled (or busy? (not (:frames @state))) :title "Restart animation" :aria-label "Restart animation"} [icon :reset]]
      [:span.control-divider]
      [:button.text-button {:on-click #(regenerate!) :title "Generate a new array"} [icon :shuffle] "Shuffle"]]
     [:div.speed-control [:label {:for "speed"} "Speed" [:strong (str (:speed @state) " steps/s")]]
      [:input {:id "speed" :type "range" :min 10 :max 500 :step 10 :value (:speed @state) :on-change #(swap! state assoc :speed (js/parseInt (.. % -target -value)))}]]]))
(defn progress []
  [:input.timeline {:type "range" :min 0 :max (max 0 (dec (count (:frames @state)))) :step 1 :value (:index @state)
                    :disabled (< (count (:frames @state)) 2) :aria-label "Animation progress"
                    :on-change #(swap! state playback/scrub (js/parseInt (.. % -target -value)))}])
(defn settings []
  [:div.settings
   [:div.array-control [:label {:for "array-size"} "Array size" [:strong (:count @state)]]
    [:input {:id "array-size" :type "range" :min (if (= :bogo (:algorithm @state)) 2 8) :max (if (= :bogo (:algorithm @state)) 8 128) :step 1 :value (:count @state)
             :on-change #(regenerate! {:count (js/parseInt (.. % -target -value))})}]]
   [:div.distribution-control [:label {:for "distribution"} "Starting order"]
    [:select {:id "distribution" :value (:distribution @state) :on-change #(regenerate! {:distribution (.. % -target -value)})}
     [:option {:value "random"} "Random"] [:option {:value "nearly"} "Nearly sorted"] [:option {:value "reversed"} "Reversed"] [:option {:value "few"} "Few unique values"]]]
   [:button.sound-button {:aria-pressed (:sound? @state) :on-click #(do (enable-audio!) (swap! state update :sound? not) (save-setting! "sort-sound" (str (:sound? @state))))}
    [icon (if (:sound? @state) :sound :mute)] (if (:sound? @state) "Sound on" "Sound off")]])
(defn overview [a]
  [:div.overview-content
   [:div.explanation [:div.section-eyebrow "THE IDEA"] [:h3 (str "How " (str/lower-case (:name a)) " works")]
    [:p (:summary a)]
    [:ol.explanation-steps (for [[i step] (map-indexed vector (:steps a))] ^{:key i} [:li [:span (str "0" (inc i))] [:p step]])]]
   [:div.complexity [:div.section-eyebrow "AT A GLANCE"]
    (for [[label value] [["Best case" (:best a)] ["Average case" (:average a)] ["Worst case" (:worst a)] ["Extra space" (:space a)]]]
      ^{:key label} [:div.complexity-row [:span label] [:strong value]])
    [:div.stability [:span "Stable sort"] [:span (if (:stable? a) "Yes" "No")]]
    [:p.complexity-note "Complexities describe the standard algorithm; animation trace storage is additional."]]])
(defn displayed-code [a app-state]
  (if (= :custom (:algorithm app-state))
    (:code app-state)
    (if (= "ClojureScript" (:language app-state))
      (:code a)
      (get-in references/codes [(:id a) (:language app-state)]))))
(defn code-view [a]
  [:div.code-content
   [:div.code-toolbar [:div [:span.code-dot] "IMPLEMENTATION"]
    [:select {:aria-label "Code language" :value (:language @state) :on-change #(swap! state assoc :language (.. % -target -value))}
     (for [lang (cons "ClojureScript" (sort (keys (get references/codes (:id a)))))] ^{:key lang} [:option {:value lang} lang])]
    [:button.text-button {:on-click #(when-let [clipboard (.-clipboard js/navigator)] (-> (.writeText clipboard (or (displayed-code a @state) "")) (.then (fn [] (swap! state assoc :copied? true) (js/setTimeout (fn [] (swap! state assoc :copied? false)) 1800))) (.catch (fn [_] (swap! state assoc :error "Clipboard access is unavailable. Select the code to copy it.")))))} [icon :copy] (if (:copied? @state) "Copied" "Copy code")]]
   [:pre [:code (or (displayed-code a @state) "Choose an algorithm to view its implementation.")]]
   [:p.code-note (if (= "ClojureScript" (:language @state)) "A concise reference implementation. The animated engine adds operation tracking." "Reference example preserved from the original Sort Visualizer.")]])
(defn playground []
  [:div.playground-content
   [:div.playground-intro [:div [:h3 "Your algorithm. Your experiment."] [:p "Write ClojureScript, then watch it run on the current array."]]
    [:button.primary-button {:disabled (:generating? @state) :on-click #(do (clear-trace!) (swap! state assoc :algorithm :custom) (.replaceState js/history nil "" "#/custom") (request-trace! :play))} [icon :play] "Run my code"]]
   [:label.sr-only {:for "custom-code"} "Custom ClojureScript algorithm"]
   [:textarea.code-editor {:id "custom-code" :spell-check false :value (:code @state)
                          :on-change #(update-code! (.. % -target -value))}]
   [:details.api-docs [:summary "Playground API & examples"]
    [:p "Return a function of one state argument, for example (fn [state] ...). Each operation returns a new state; pass that state to the next operation and return the final state:"]
    [:dl (for [[code desc] [["(size state)" "Number of values in the state."]
                           ["(value state i)" "Read the value at zero-based index i."]
                           ["(compare-at state i j)" "Return [next-state comparison], recording a frame. The comparison is negative, zero, or positive."]
                           ["(swap-at state i j)" "Return a new state with two values exchanged and a frame recorded."]
                           ["(write-at state i v)" "Return a new state with a finite numeric value written and a frame recorded."]
                           ["(snapshot state [i j] \"note\")" "Return a new state with highlighted indices and an explanation."]]]
           ^{:key code} [:div [:dt [:code code]] [:dd desc]])]
    [:p "Runs are isolated in a worker, limited to 8 seconds and a bounded number of operations. The final array must be sorted and preserve every input value."]]
   [:button.text-button {:on-click #(update-code! custom/default-code)} [icon :reset] "Restore example"]])
(defn learning []
  (let [a (current-algorithm @state)]
    [:section.learning {:aria-label "Learn about the algorithm"}
     [:div.tabs {:role "tablist" :aria-label "Algorithm details"}
      (for [[id title] [[:overview "Overview"] [:code "Source code"] [:playground "Playground"]]]
        ^{:key id} [:button {:role "tab" :id (str "tab-" (name id)) :aria-controls "learning-panel" :aria-selected (= id (:tab @state)) :class (when (= id (:tab @state)) "active") :on-click #(swap! state assoc :tab id)} title (when (= id :playground) [:span.tab-tag "TRY IT"])])
      [:span.language-badge "( )  MADE WITH CLOJURESCRIPT"]]
     [:div {:id "learning-panel" :role "tabpanel" :aria-labelledby (str "tab-" (name (:tab @state)))}
      (case (:tab @state) :code [code-view a] :playground [playground] [overview a])]]))
(defn app []
  (let [a (current-algorithm @state) f (playback/current-frame @state)]
    [:div.app-shell
     [:header.site-header [:a.brand {:href "#/quick" :on-click #(select-algorithm! :quick)} [logo] [:span "sort" [:strong "visualizer"]] [:span.brand-edition "CLJS"]]
      [:div.header-right [:span.header-note "A LITTLE ORDER. A LOT TO LEARN."] [:a.source-link {:href "https://github.com/ultimatedbz/sortVisualizerClojureScript" :target "_blank" :rel "noreferrer"} "View source" [icon :arrow]]]]
     [:main
      [:section.intro [:div [:div.eyebrow [:span.status-dot] "THE INTERACTIVE SORTING LAB"]
                      [:h1 "Bring order to the " [:em "chaos."]]
                      [:p "Pick an algorithm. Watch it think. See the patterns emerge."]]
       [:div.intro-index [:span "15"] [:div "ALGORITHMS" [:br] "ENDLESS POSSIBILITIES"]]]
      [:div.lab-layout [sidebar]
       [:div.workspace
        [:section.sort-panel {:aria-label "Sorting visualizer"}
         [:div.panel-heading [:div [:div.section-eyebrow (:family a)] [:h2 (:name a) [:span.algorithm-badge (or (:average a) "Your code")]]]
          [:div.metrics [:div [:span "COMPARISONS"] [:strong (.toLocaleString (:comparisons f))]] [:div [:span "WRITES"] [:strong (.toLocaleString (:writes f))]]]]
         [settings] [visualization] [progress] [controls]
         (when (:error @state) [:div.error-message {:role "alert"} (:error @state)])
         (when (and (= :limited (:result-status @state)) (not (:playing? @state)))
           [:div.notice-message (:result-message @state)])]
        [learning]]]
      [:footer [:span "Built for the curious."] [:span "ClojureScript rewrite of " [:a {:href "https://github.com/Myphz/sortvisualizer" :target "_blank" :rel "noreferrer"} "Myphz / sortvisualizer"] [:span.footer-dot "·"] "MIT licensed"]]]]))
(defn render! []
  (when-not @root (reset! root (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @root [app]))
(defn read-route! []
  (let [id (keyword (str/replace (.-hash js/location) #"^#/?" ""))]
    (when (or (by-id id) (= id :custom))
      (when-not (= id (:algorithm @state)) (select-algorithm! id))
      (when (= id :custom) (swap! state assoc :tab :playground)))))
(defn init! []
  (read-route!)
  (.addEventListener js/window "hashchange" read-route!)
  (when @ticker (js/clearInterval @ticker))
  (reset! ticker (js/setInterval tick! 16))
  (render!))
