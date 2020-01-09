# Datafire

Persist [Datascript](https://github.com/tonsky/datascript) databases in [Firebase's Firestore](https://firebase.google.com/docs/firestore).

Supports offline usage and multi-user synchronization. 
You can use [Firestore Security Rules](https://firebase.google.com/docs/firestore/security/get-started) to limit access.

## Usage

```Clojure
(ns app.quickstart
  (:require ["firebase/app" :as firebase]
            [datascript.core :as d]
            [datafire.core :as df]))

; Initialize the Firebase app with your configuration.
(defonce firebase-config #js { ... })
(defonce app (let [app (.initializeApp firebase firebase-config)
                  ; Enable offline persistence.
                   _ (.enablePersistence (.firestore app))]
               app))

; Create a Datascript connection.
(defonce conn (d/create-conn))

; Create a link between the Datascript connection and the "dbs/quickstart" Firestore document path.
(defonce link (df/create-link conn "dbs/quickstart"))

; Start listening to transactions on Firebase and applies them to the Datascript connection.
; Previous transactions will be loaded onto the Datascript connection.
(defonce _ (df/listen! link))

; Persist tx-data on the link.
; Returns a promise that resolves when the transaction hits the server.
; Since the promise won't resolve while offline, it's recommended that you never wait for it.
(df/transact! link [{:msg (str "Persisted on " (.toISOString (js/Date.)))}])

; Query data using the Datascript connection.
(d/q '[:find  ?m
       :where [?e :name ?m]]
     @conn)
; #{[Persisted on 2020-01-09T15:54:02.065Z] 
;   [Persisted on 2020-01-09T15:54:20.310Z] 
;   [Persisted on 2020-01-09T15:55:23.964Z] 
;   ...}
```