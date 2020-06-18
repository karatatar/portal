(ns sedit.core
  (:require [reagent.core :as r]
            [sedit.styled :as s]
            [clojure.string :as str]
            [cognitect.transit :as t]))

(defn index-value
  ([value]
   (index-value [] value))

  ([path value]
   (cond
     (map? value)
     (mapcat
      (fn [[k v]]
        (let [path (conj path k)]
          (concat
           (index-value path k)
           (index-value path v))))
      value)

     (coll? value)
     (apply
      concat
      (map-indexed
       (fn [k v]
         (let [path (conj path k)]
           (concat
            (index-value path k)
            (index-value path v))))
       value))

     :else
     [{:path path
       :value value
       :string-value (str/lower-case (pr-str value))}])))

(defn filter-index [index s]
  (filter #(str/includes? (:string-value %)
                          (str/lower-case s))
          index))

(declare sedit)

(defn collapsible []
  (let [state (r/atom {:open? true})]
    (fn [props child]
      [s/div
       {:on-click #(do
                     (swap! state update :open? not)
                     (.stopPropagation %))}
       (let [{:keys [open?]} @state]
         (if-not open?
           (:hidden props)
           child))])))

(defn get-background [settings]
  (if (even? (:depth settings))
    (:colors/background settings)
    (:colors/background2 settings)))

(defn summary [settings value]
  (when-let [[open close]
             (cond
               (map? value)     ["{" "}"]
               (vector? value)  ["[" "]"]
               (seq? value)     ["(" ")"]
               (set? value)     ["#{" "}"])]
    [s/div
     {:style {:vertical-align :top
              :color "#bf616a"}}
     open (count value) close]))

(defn table-view? [value]
  (and (coll? value) (every? map? value)))

(defn sedit-table [settings values]
  (let [settings (update settings :depth inc)]
    (if (> (:depth settings) (:limits/max-depth settings))
      [summary settings values]
      (let [columns (into #{} (mapcat keys values))
            background (get-background settings)]
        [s/table
         {:style
          {:width "100%"
           :border-collapse :collapse
           :color (:colors/text settings)
           :font-size  (:font-size settings)
           :border-radius (:border-radius settings)}}
         [s/tbody
          [s/tr
           (map-indexed
            (fn [grid-column column]
              [s/th {:key grid-column
                     :style
                     {:border (str "1px solid " (:colors/border settings))
                      :background background
                      :box-sizing :border-box
                      :padding (:spacing/padding settings)}}
               [sedit settings column]])
            columns)]
          (map-indexed
           (fn [grid-row row]
             [s/tr {:key grid-row}
              (map-indexed
               (fn [grid-column column]
                 [s/td
                  {:key grid-column
                   :style
                   {:border (str "1px solid " (:colors/border settings))
                    :background background
                    :padding (:spacing/padding settings)
                    :box-sizing :border-box}}
                  (when-let [value (get row column)]
                    [sedit settings value])])
               columns)])
           values)]]))))

(defonce path (r/atom []))

(defn sedit-map [settings values]
  (let [settings (update settings :depth inc)]
    (if (> (:depth settings) (:limits/max-depth settings))
      [summary settings values]
      [s/div
       {:style
        {:width "100%"
         :display :grid
         :background (get-background settings)
         :grid-gap (:spacing/padding settings)
         :padding (:spacing/padding settings)
         :box-sizing :border-box
         :color (:colors/text settings)
         :font-size  (:font-size settings)
         :border-radius (:border-radius settings)
         :border (str "1px solid " (:colors/border settings))}}

       #_[s/thead
          [s/tr
           {:style
            {:border-bottom (str "1px solid " (:colors/border settings))}}
           [s/td
            [s/div
             {:style {:display :flex}}
             type+count]]
           [s/td]]]
       (take
        (:limits/max-length settings)
        (filter
         some?
         (for [[k v] values]
           (let [sedit-k [sedit settings k]
                 sedit-v [sedit settings v]]
             [:<>
              {:key (hash k)}
              [s/div {:style
                      {:grid-column "1"}}
               [s/div
                {:style {:display :flex}}
                sedit-k]]

              #_[s/td {:style
                       {:vertical-align :top
                        :text-align :left
                        :padding (:spacing/padding settings)}}
                 [summary settings v]]

              [s/div {:style
                      {:grid-column "2"
                       :text-align :right}}

               sedit-v]]))))])))

(defn sedit-coll [settings values]
  (let [settings (update settings :depth inc)]
    (if (> (:depth settings) (:limits/max-depth settings))
      [summary settings values]
      [s/div
       {:key (hash values)
        :style
        {:text-align :left
         :display :grid
         :background (get-background settings)
         :grid-gap (:spacing/padding settings)
         :padding (:spacing/padding settings)
         :box-sizing :border-box
         :color (:colors/text settings)
         :font-size  (:font-size settings)
         :border-radius (:border-radius settings)
         :border (str "1px solid " (:colors/border settings))}}

       #_[s/div
          {:style
           {:border-bottom (str "1px solid " (:colors/border settings))}}
          type+count]
       (->> values
            (map-indexed
             (fn [idx itm]
               ^{:key idx}
               [sedit settings itm]))
            (filter some?)
            (take (:limits/max-length settings)))])))

(defn trim-string [settings s]
  (let [max-length (:limits/string-length settings)]
    (if-not (> (count s) max-length)
      s
      (str (subs s 0 max-length) "..."))))

(defn text-search [settings value]
  (cond
    (nil? (:input/text-search settings)) value

    (some? value)
    (let [text (:input/text-search settings)
          len  (count text)
          i    (str/index-of (str/lower-case value)
                             (str/lower-case text))]
      (when-not (nil? i)
        (let [before  (subs value 0 i)
              match   (subs value i (+ len i))
              after   (subs value (+ len i))]
          [s/span before [:mark match] after])))))

(def viewers
  {:sedit.viewer/map    {:predicate map?        :component sedit-map}
   :sedit.viewer/table  {:predicate table-view? :component sedit-table}
   :sedit.viewer/coll   {:predicate coll?       :component sedit-coll}})

(defn sedit [settings value]
  [s/div
   {:on-click
    (fn [e]
      (when-not (zero? (:depth settings))
        (.stopPropagation e)
        ((:sedit/on-nav settings) value)))
    :style {:cursor :pointer
            :border-radius (:border-radius settings)
            :border "1px solid rgba(0,0,0,0)"}
    :style/hover {:border
                  (when-not (zero? (:depth settings))
                    "1px solid #D8DEE9")}}
   (cond
     (map? value)
     [sedit-map settings value]

     (coll? value)
     [sedit-coll settings value]

     (boolean? value)
     [s/span {:style {:color (:colors/boolean settings)}}
      (pr-str value)]

     (symbol? value)
     [s/span {:style {:color (:colors/symbol settings)}}
      value]

     (number? value)
     [s/span {:style {:color (:colors/number settings)}}
      value]

     (string? value)
     [s/span {:style {:color (:colors/string settings)}}
      (pr-str (trim-string settings value))]

     (keyword? value)
     (let [keyword-name (name value)
           keyword-namespace (namespace value)]
       (when keyword-name
         [s/span {:style {:color (:colors/keyword settings) :white-space :nowrap}}
          ":" (when keyword-namespace
                [s/span {:style {:color (:colors/keyword-namespace settings)}}
                 keyword-namespace
                 "/"])
          keyword-name]))

     (instance? js/Date value)
     [s/span {:style {:color (:colors/date settings)}}
      (pr-str value)]

     (instance? cljs.core/UUID value)
     [s/span {:style {:color (:colors/uuid settings)}}
      (pr-str value)]

     (instance? cljs.core/Var value)
     [s/span {:style {:color (:colors/var settings)}}
      (pr-str value)]

     :else
     [s/span {}
      (trim-string settings (pr-str value))])])

(defn sedit-1 []
  (let [selected-viewer (r/atom nil)]
    (fn [settings value]
      (let [compatible-viewers
            (into #{} (keep (fn [[k {:keys [predicate]}]]
                              (when (predicate value) k)) viewers))
            viewer    (or @selected-viewer (first compatible-viewers))
            component (if-not (contains? compatible-viewers viewer)
                        sedit
                        (get-in viewers [viewer :component] sedit))]
        [s/div
         {:style
          {:flex 1}}
         [s/div
          {:style
           {:position :relative
            :min-height "calc(100% - 64px)"
            :max-height "calc(100% - 64px)"
            :min-width "100%"
            :box-sizing :border-box
            :border (str "1px solid " (:colors/border settings))}}
          [:div
           {:style
            {:position :absolute
             :top 0
             :left 0
             :right 0
             :bottom 0
             :overflow :auto
             :box-sizing :border-box
             :padding 20}}
           [:div
            [component settings value]]]]
         (when-not (empty? compatible-viewers)
           [:select
            {:value (pr-str viewer)
             :on-change #(reset! selected-viewer
                                 (keyword (.substr (.. % -target -value) 1)))
             :style
             {:background (:colors/background settings)
              :margin (:spacing/padding settings)
              :padding (:spacing/padding settings)
              :box-sizing :border
              :font-size (:font-size settings)
              :color (:colors/text settings)
              :border (str "1px solid " (:colors/border settings))}}
            (for [k compatible-viewers]
              [:option {:key k :value (pr-str k)} (pr-str k)])])]))))

(def themes
  {:themes/nord
   {:colors/text "#d8dee9"
    :colors/background "#2e3440"
    :colors/background2 "rgba(0,0,0,0.1)"
    :colors/boolean "#5e81ac"
    :colors/string "#a3be8c"
    :colors/keyword "#5e81ac"
    :colors/keyword-namespace "#88c0d0"
    :colors/symbol "#d8dee9"
    :colors/number "#b48ead"
    :colors/date "#ebcb8b"
    :colors/uuid "#d08770"
    :colors/var "#88c0d0"
    :colors/border "#4c566a"}})

(def default-settings
  (merge
   {:font/family "monospace"
    :font-size "12pt"
    :limits/string-length 100
    :limits/max-depth 2
    :limits/max-panes 1
    :limits/max-length 1000
    :layout/direction :row
    :spacing/padding "10px"
    :border-radius "2px"
    :sedit/history '()}
   (:themes/nord themes)))

(comment
  (true? (swap! state assoc :sedit/value example))
  (true? (swap! state assoc :spacing/padding "10px")))

(def example
  {:example/booleans #{true false}
   :example/nil nil
   :example/vector [1 2 4]
   "string-key" "string-value"
   :example/list (list 1 2 3)
   :example/set #{1 2 3}
   {:example/settings default-settings} :hello-world
   #{1 2 3} [4 5 6]
   :example/date (js/Date.)
   :example/var #'default-settings
   :example/uuid (random-uuid)
   :example/nested-vector [1 2 3 [4 5 6]]
   :example/code '(defn hello-world [] (println "hello, world"))})

(defonce state (r/atom default-settings))

(defonce search-text (r/atom ""))

(defn search-input [settings]
  [s/input
   {:on-change #(reset! search-text (.-value (.-target %)))
    :value @search-text
    :style
    {:flex "1"
     :background (:colors/background settings)
     :margin (:spacing/padding settings)
     :padding (:spacing/padding settings)
     :box-sizing :border
     :font-size (:font-size settings)
     :color (:colors/text settings)
     :border (str "1px solid " (:colors/border settings))}}])

(defn search-results [settings]
  (let [search-text-value @search-text]
    (when-not (str/blank? search-text-value)
      [:<>
       (->>
        search-text-value
        (filter-index (:sedit/index settings))
        (take 10)
        (map-indexed
         (fn [index item]
           [s/div
            {:key index :on-click #(do
                                     (reset! search-text nil)
                                     (reset! path (:path item)))}
            [sedit settings (dissoc item :string-value)]])))])))

(defn toolbar-button-styles [settings]
  {:background (:colors/text settings)
   :color (:colors/background settings)
   :font-size (:font-size settings)
   :border :none
   :box-sizing :border-box
   :padding "10px 20px"
   :border-radius (:border-radius settings)
   :cursor :pointer
   :margin "0 20px"})

(defn toolbar [settings path]
  [s/div
   {:style
    {:height "64px"
     :flex-direction :row
     :display :flex
     :align-items :center
     :justify-content :center
     :border-bottom  (str "1px solid " (:colors/border settings))}}
   [s/button
    {:on-click (:sedit/on-back settings)
     :style    (toolbar-button-styles settings)} "back"]
   [search-input settings]
   [s/button
    {:on-click (:sedit/on-clear settings)
     :style    (toolbar-button-styles settings)} "clear"]])

(defn json->edn [json]
  (let [r (t/reader :json)]
    (t/read r json)))

(defn edn->json [edn]
  (let [w (t/writer :json)]
    (t/write w edn)))

(defn send-rpc!
  ([msg] (send-rpc! msg identity))
  ([msg done]
   (-> (js/fetch
        "/rpc"
        #js {:method "POST" :body (edn->json msg)})
       (.then #(.text %))
       (.then json->edn)
       (.then done))))

(defn merge-state [new-state]
  (when-not (:sedit/open? new-state)
    (js/window.close))
  (let [index (index-value (:sedit/value new-state))
        new-state-with-index
        (assoc new-state :sedit/index index)]
    (swap! state merge new-state-with-index)))

(defn load-state! []
  (send-rpc! {:op             :sedit.rpc/load-state
              :sedit/state-id (:sedit/state-id @state)}
             merge-state))

(defn clear-values! []
  (send-rpc! {:op :sedit.rpc/clear-values}
             #(swap! state assoc :sedit/history '())))

(defn get-history-stack [settings]
  (if (empty? (:sedit/history settings))
    [(list (:sedit/value settings))]
    (loop [ls (:sedit/history settings) result []]
      (if (empty? ls)
        result
        (recur (rest ls) (conj result ls))))))

(defn app []
  (let [settings
        (assoc @state
               :depth 0
               :sedit/on-clear #(clear-values!)
               :sedit/on-nav
               (fn [history]
                 (swap! state assoc :sedit/history history))
               :sedit/on-back #(swap! state update :sedit/history rest))]
    [s/div
     {:style
      {:display :flex
       :flex-direction :column
       :background (:colors/background settings)
       :color (:colors/text settings)
       :font-family (:font/family settings)
       :font-size (:font-size settings)
       :height "100vh"
       :width "100vw"}}
     [toolbar settings path]
     [s/div {:style {:height "calc(100vh - 64px)" :width "100vw"}}
      (if-not (str/blank? @search-text)
        [search-results settings]
        [:div
         {:style
          {:width "100%"
           :height "100%"
           :display :flex}}
         (->>
          (get-history-stack settings)
          (take (:limits/max-panes settings))
          reverse
          (map-indexed
           (fn [idx ls]
             [:<>
              {:key idx}
              [sedit-1
               (update settings
                       :sedit/on-nav
                       (fn [on-nav] #(on-nav (conj ls %))))
               (first ls)]])))])]]))

(defn promise-loop [f]
  (.finally (f) #(promise-loop f)))

(defn render-app []
  (r/render [app]
            (.getElementById js/document "root")))

(defn main! []
  (promise-loop load-state!)
  (render-app))

(defn reload! [] (render-app))