(ns app.hello-cards
  ; We shouldn't need to load reagent directly, but it looks like devcards
  ; is importing it in such a way that it needs to be imported beforehand.
  (:require [reagent.core]
            [devcards.core :refer [defcard defcard-rg]]
            [datascript.core :as d]
            [datascript-firebase.core :as df]
            ["firebase/app" :as firebase]
            ["firebase/firestore"]
            [app.db :refer [datascript-conn firebase-conn]]))

(defn parse-fb-snapshot [query-snapshot]
  (map #(assoc (js->clj (.data %) :keywordize-keys true) :id (.-id %))
       (.-docs query-snapshot)))

(defn fb-tx-atom []
  (let [a (atom [])]
    (.onSnapshot (.collection (.firestore firebase) "tx")
                 #(reset! a (parse-fb-snapshot %)))
    a))

(defn ds-add-user [user]
  (df/save-transaction! firebase-conn [user]))

(defn add-ada []
  (let [ada {:db/id -1 :first "Ada" :last "Lovelace" :born "1815"}]
    [:<>
     [:div
      "Click to add an Ada Lovelace user "
      [:input {:type "button" :value "Add Ada"
               :on-click #(ds-add-user ada)}]]
     [:div
      "Click to clear the firebase emulator database and reload "
      [:input {:type "button" :value "Clear"
               :on-click #(do (js/fetch
                               "http://localhost:8080/emulator/v1/projects/datascript-firebase/databases/(default)/documents"
                               #js {:method "DELETE"})
                              (.reload js/window.location))}]]]))

; Different approach:
; - use something that guarantees ordering, but not necessarily uniqueness, (database pushids,
; firestore servertime) to store whole transactions, and use that as the source of truth.
; - use something that guarantees uniqueness, but not necessarily order, (database pushids, 
; firestore doc ids) for server-eid
; - push transactions to firebase and listen for the fb tx to add them to ds
; - add server-eids to transactions going to firebase: tempids get new server-ids, non-tempids
;   look up the existing server-eid locally (to reference something that already exists, it 
;   is there already by definition)
; - use a queue to decouple receiving new tx from consuming them (not sure if this can be a 
;   problem at all if the ds api is sync?)
; - use a local eid->server-id memoized lookup index
; TODO: figure out how to handle refs between datoms, presumably there's a way
; Refs show up in the schema as `:db.type/ref`. I think I'll need to have a server version of
; each ref in the schema

(defcard-rg add-ada-card add-ada)

(defcard ds-conn datascript-conn)

(defcard firestore-tx (fb-tx-atom) [] {:history false})

#_(defcard fb-conn firebase-conn)


; clear db
; https://firebase.google.com/docs/emulator-suite/connect_and_prototype?database=Firestore

; cache folders for emulators
; https://firebase.google.com/docs/emulator-suite/install_and_configure#integrate_with_your_ci_system