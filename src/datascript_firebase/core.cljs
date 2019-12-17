(ns datascript-firebase.core
  (:require [datascript.core :as d]
            [datascript.transit :as dt]
            ["firebase/app" :as firebase]))

(def seid-key :datascript-firebase/seid)
(def seid-schema {seid-key {:db/unique :db.unique/identity
                            :db/index true}})

(defn- new-seid [conn]
  ; Note: this doesn't actually create a doc.
  (.-id (.doc (.collection (.firestore firebase) (:path conn)))))

(defn- datom->op [datom]
  [(if (pos? (:tx datom))
     :db/add
     :db/retract)
   (:e datom) (:a datom) (:v datom)])

; TODO:
; - add serverside timestamp
; - add fb index (if needed? it might be auto)
(defn- save-to-firestore! [conn tx-data]
  (.add (.collection (.firestore firebase) (:path conn)) 
        #js {:t (dt/write-transit-str tx-data)}))

; circle back on the firestore-transact! to look up seid refs
(defn- load-transaction! [conn tx-data]
  (loop [input-ops tx-data
         output-ops []
         seid->tempid {}
         max-tempid 0]
    (if (empty? input-ops)
      (d/transact! (:datascript-conn conn) output-ops)
      (let [op (first input-ops)
            fb-eid (op 1)
            seid (and (= seid-key (op 2)) (op 3))
            new-max-tempid (if seid (dec max-tempid) max-tempid)
            new-seid->temp-id (if seid
                                (assoc seid->tempid seid new-max-tempid)
                                seid->tempid)
            ds-eid (or (get new-seid->temp-id fb-eid)
                       (:db/id (d/entity @(:datascript-conn conn) [seid-key fb-eid])))
            new-op (assoc op 1 ds-eid)]
        (if (nil? ds-eid)
          (throw (str "Could not find eid for " seid))
          (recur (rest input-ops)
                 (conj output-ops new-op)
                 new-seid->temp-id
                 new-max-tempid))))))

; Notes from transact! doc:
; https://cljdoc.org/d/d/d/0.18.7/api/datascript.core#transact!
; - tempid can be string 
; - whats :db/add ?
; - reverse attr names can also be refs
; - what about tx-meta?
; - :tempids in report also contains :db/current-tx, but doesn't contain the new id
;   for entities that don't have a :db/id set
; - does d/transact! accept datoms? yes
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
(defn save-transaction! [conn tx]
  (loop [tx-data (:tx-data (d/with @(:datascript-conn conn) tx))
         ops []
         eid->seid {}]
    (if (empty? tx-data)
      (save-to-firestore! conn ops)
      (let [datom (first tx-data)
            eid (:e datom)
            new? (not (contains? eid->seid eid))
            seid (get eid->seid (:e datom) (new-seid conn))
            new-ops (conj ops (datom->op (assoc datom :e seid)))]
        (recur (rest tx-data)
                 ; Prepend seid ops, append others. 
                 ; This way the seid op will always be read first.
               (if new?
                 (vec (concat [[:db/add seid seid-key seid]] new-ops))
                 new-ops)
               (if new?
                 (assoc eid->seid eid seid)
                 eid->seid))))))

(defn create-conn
  ([path] (create-conn path nil))
  ([path schema]
   (with-meta
     {:datascript-conn (d/create-conn (merge schema seid-schema))
      :path path
      :known-ids (atom #{})}
     {:unsubscribe (atom nil)})))

(defn unlisten! [conn]
  (let [unsubscribe @(:unsubscribe (meta conn))]
    (when unsubscribe (unsubscribe))
    (reset! (:unsubscribe (meta conn)) nil)))

(defn listen! 
  ([conn] (listen! conn js/undefined))
  ([conn error-cb] 
   (unlisten! conn)
   (reset! (:unsubscribe (meta conn))
           (.onSnapshot (.collection (.firestore firebase) (:path conn))
                        (fn [snapshot]
                          (.forEach (.docChanges snapshot)
                                    #(let [data (.data (.-doc %))
                                           id (.-id (.-doc %))]
                                       (when (and (= (.-type %) "added")
                                                  (not (contains? @(:known-ids conn) id)))
                                         (load-transaction! conn (dt/read-transit-str (.-t data)))
                                         (swap! (:known-ids conn) conj id)))))
                        error-cb))))