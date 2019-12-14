(ns app.db
  (:require [datascript.core :as datascript]
            ["firebase/app" :as firebase]
            ["firebase/firestore"]))

(def firebase-config #js {:apiKey "AIzaSyAYJX2_LdpTbdgcaGYvSbfz9hJplqTPi7Y"
                          :authDomain "datascript-firebase.firebaseapp.com"
                          :databaseURL "https://datascript-firebase.firebaseio.com"
                          :projectId "datascript-firebase"
                          :storageBucket "datascript-firebase.appspot.com"
                          :messagingSenderId "990887725503"
                          :appId "1:990887725503:web:54a0534e1ba1c52a2e390a"})

(defonce initialize-firestore-app (.initializeApp firebase firebase-config))

(defn fb-user-coll []
  (let [db (.firestore firebase)
        ; Use the emulator. This should probably be a goog.define instead.
        _ (when true (.settings db #js {:host "localhost:8080"
                                        :ssl false}))] 
    (.collection db "users")))

(defn parse-fb-snapshot [query-snapshot]
  (map #(assoc (js->clj (.data %) :keywordize-keys true) :id (.-id %))
       (.-docs query-snapshot)))

(defn fb-user-atom []
  (let [users-atom (atom [])]
    (.onSnapshot (fb-user-coll)
                 #(reset! users-atom (parse-fb-snapshot %)))
    users-atom))

(defn fb-add-user [user]
  (.add (fb-user-coll) (clj->js user)))

(defonce datascript-connection (datascript/create-conn))

(defn datascript-datoms []
  (let [users-atom (atom [])]
    (datascript/listen! datascript-connection
                        #(reset! users-atom (datascript/datoms @datascript-connection :eavt)))
    users-atom))
  

(defn ds-add-user [user]
  (datascript/transact! datascript-connection [user]))
