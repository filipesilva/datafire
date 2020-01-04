(ns app.sandbox
  (:require [reagent.core]
            [devcards.core :refer [defcard defcard-rg]]
            [datascript.core :as d]
            [datascript-firebase.core :as df]
            ["firebase/app" :as firebase]
            [app.test-helpers :refer [test-link]]))

(defonce sandbox-test-link (test-link))
(def conn (sandbox-test-link 0))
(def link (sandbox-test-link 1))
(def path (sandbox-test-link 2))
(def fs (.firestore (.app firebase (sandbox-test-link 3))))

(defn parse-fb-snapshot [query-snapshot]
  (map #(assoc (js->clj (.data %) :keywordize-keys true) :id (.-id %))
       (.-docs query-snapshot)))

(defn fb-tx-atom []
  (let [a (atom [])]
    (.onSnapshot (.collection fs path)
                 #(reset! a (parse-fb-snapshot %)))
    a))

(defn add-user [user]
  (df/transact! link [user]))

(defn pull-2 []
  (print (d/pull @conn '[*] 2)))

(defn add-ada []
  (let [ada {:db/id -1 :first "Ada" :last "Lovelace" :born "1815"}
        ada-ref {:db/id -1 :ada-ref 1}]
    [:<>
     [:div
      "Click to add an Ada Lovelace user "
      [:input {:type "button" :value "Add Ada"
               :on-click #(add-user ada)}]]
     [:div
      "Click to add an ada-ref to 1 "
      [:input {:type "button" :value "Add Ada ref"
               :on-click #(add-user ada-ref)}]]
     [:div
      "Click to pull on 2 "
      [:input {:type "button" :value "pull 2"
               :on-click #(pull-2)}]]
     [:div
      "Click to clear the firebase emulator database and reload "
      [:input {:type "button" :value "Clear"
               :on-click #(.then (js/fetch
                                  "http://localhost:8080/emulator/v1/projects/datascript-firebase/databases/(default)/documents"
                                  #js {:method "DELETE"})
                                 (fn [] (.reload js/window.location)))}]]
     [:div
      "Click to disable network "
      [:input {:type "button" :value "disable"
               :on-click #(.disableNetwork fs)}]]
     [:div
      "Click to enable network "
      [:input {:type "button" :value "enable"
               :on-click #(.enableNetwork fs)}]]]))

(defcard-rg add-ada-card add-ada)

(defcard ds-conn conn)

(defcard firestore-tx (fb-tx-atom) [] {:history false})
