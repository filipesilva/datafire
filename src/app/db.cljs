(ns app.db
  (:require [datascript.core :as d]
            [datascript-firebase.core :as df]
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
(defonce use-emulator
  ; Use the emulator. This should probably be a goog.define instead.
  (.settings (.firestore firebase) #js {:host "localhost:8080"
                                        :ssl false}))
(defonce datascript-connection (d/create-conn))
(defonce firebase-connection (df/create-conn datascript-connection "tx"))
(defonce listed-fb-conn (df/listen! firebase-connection))
