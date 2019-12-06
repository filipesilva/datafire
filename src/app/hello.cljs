(ns app.hello
  (:require [app.db :refer [fb-add-user ds-add-user]]))

(defn add-ada []
  (let [ada {:first "Ada" :last "Lovelace" :born "1815"}]
    [:div
     "Click to add an Ada Lovelace user "
     [:input {:type "button" :value "Click me!"
              :on-click #(do 
                           (fb-add-user ada)
                           (ds-add-user ada))}]]))