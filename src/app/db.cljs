(ns app.db
  (:require [datascript.core :as datascript]
            [datascript.transit :as dt]
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

(defn fb-user-coll []
    (.collection (.firestore firebase) "users"))

(defn parse-fb-snapshot [query-snapshot]
  (map #(assoc (js->clj (.data %) :keywordize-keys true) :id (.-id %))
       (.-docs query-snapshot)))

(defn fb-add-user [user]
  (.add (fb-user-coll) (clj->js user)))

(defonce datascript-connection (datascript/create-conn))

(defn datascript-datoms []
  (let [users-atom (atom [])]
    (datascript/listen! datascript-connection
                        #(reset! users-atom (datascript/datoms @datascript-connection :eavt)))
    users-atom))


(defn fb-seid-coll []
    (.collection (.firestore firebase) "seid"))

(defn fb-tx-coll []
    (.collection (.firestore firebase) "tx"))

(defn fb-tx-atom []
  (let [a (atom [])]
    (.onSnapshot (fb-tx-coll)
                 #(reset! a (parse-fb-snapshot %)))
    a))

; Is this ok or should I use something else?
; it's ok if it's long-ish, there's only one extra datom for each entity.
(def seid-key :datascript-firebase/seid)

(defn new-seid []
  ; Note: this doesn't actually create a doc.
  (.-id (.doc (fb-seid-coll))))

(defn datom->fb-op [datom]
  [(if (pos? (:tx datom))
     :db/add
     :db/retract)
   (:e datom) (:a datom) (:v datom)])

(defn save-to-firestore! [tx-data]
  (.add (fb-tx-coll) #js {:t (dt/write-transit-str tx-data)}))

; Notes from transact! doc:
; https://cljdoc.org/d/datascript/datascript/0.18.7/api/datascript.core#transact!
; - tempid can be string 
; - whats :db/add ?
; - reverse attr names can also be refs
; - what about tx-meta?
; - :tempids in report also contains :db/current-tx, but doesn't contain the new id
;   for entities that don't have a :db/id set
; - does datascript/transact! accept datoms? yes
; - maybe it's easier to check the datoms for new ids in db:add instead.
; Notes from datascript.db/transact-tx-data
; - :db/add can have tempids too, and tx-data can have :db/add
; TODOs: 
; - test/figure out retracts `[1 :name "Ivan" 536870918 false]`
;   - negative tx means retract
; - figure out other ds built-ins ever appear as the op in tx-datoms (see builtin-fn?)
; - figure out refs
; - maybe call it save-transaction!
; - separate the collection add from this operation to make it reusable for
;   both db types
; - add spec to validate data coming in and out
; - really need to revisit tx/tx-data/ops names
; - add error-cb?
(defn save-transaction! [tx]
  (loop [tx-data (:tx-data (datascript/with @datascript-connection tx))
         ops []
         eid->seid {}]
    (if (empty? tx-data)
      (save-to-firestore! ops)
      (let [datom (first tx-data)
            eid (:e datom)
            new? (not (contains? eid->seid eid))
            seid (get eid->seid (:e datom) (new-seid))
            new-ops (conj ops (datom->fb-op (assoc datom :e seid)))]
        (recur (rest tx-data)
                 ; Prepend seid ops, append others. 
                 ; This way the seid op will always be read first.
               (if new?
                 (vec (concat [[:db/add seid seid-key seid]] new-ops))
                 new-ops)
               (if new?
                 (assoc eid->seid eid seid)
                 eid->seid))))))

; circle back on the firestore-transact! to look up seid refs
(defn- load-transaction! [conn tx-data]
  (loop [input-ops tx-data
         output-ops []
         seid->tempid {}
         max-tempid 0]
    (if (empty? input-ops)
      (datascript/transact! (:ds-conn @conn) output-ops)
      (let [op (first input-ops)
            fb-eid (op 1)
            seid (and (= seid-key (op 2)) (op 3))
            new-max-tempid (if seid (dec max-tempid) max-tempid)
            new-seid->temp-id (if seid
                                (assoc seid->tempid seid new-max-tempid)
                                seid->tempid)
            ; seid-key needs to be unique attr, reference or marked as `:db/index true`
            ; https://cljdoc.org/d/datascript/datascript/0.18.7/api/datascript.core#datoms
            ds-eid (or (get new-seid->temp-id fb-eid)
                       (first (datascript/datoms @(:ds-conn @conn) :avet seid-key fb-eid)))
            new-op (assoc op 1 ds-eid)]
        (if (and (nil? ds-eid) (nil? seid))
          (throw (str "Could not find eid for " seid " and op was not for " seid-key))
          (recur (rest input-ops)
                 (conj output-ops new-op)
                 new-seid->temp-id
                 new-max-tempid))))))

(defn ds-add-user [user]
  (save-transaction! [user]))

(defn create-conn
  ([conn path] (create-conn conn path :firestore))
  ([conn path type] (atom {:ds-conn conn
                           :path path
                           :type type
                           :known-ids #{}}
                          :meta {:unsubscribe (atom nil)})))

(defn unlisten! [conn]
  (let [unsubscribe @(:unsubscribe (meta conn))]
    (when unsubscribe (unsubscribe))
    (reset! (:unsubscribe (meta conn)) nil)))

(defn listen! 
  ([conn] (listen! conn js/undefined))
  ([conn error-cb] 
   (unlisten! conn)
   (reset! (:unsubscribe (meta conn))
           (.onSnapshot (.collection (.firestore firebase) (:path @conn))
                        (fn [snapshot]
                          (.forEach (.docChanges snapshot)
                                    #(let [data (.data (.-doc %))
                                           id (.-id (.-doc %))]
                                       (when (and (= (.-type %) "added")
                                                  (not (contains? (:known-ids @conn) id)))
                                         (load-transaction! conn (dt/read-transit-str (.-t data)))
                                         (swap! conn update :known-ids conj id)))))
                        error-cb))))

(defonce firebase-connection (create-conn datascript-connection "tx"))
(defonce listed-fb-conn (listen! firebase-connection))
