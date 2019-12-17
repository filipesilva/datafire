(ns datascript-firebase.core
  (:require [datascript.core :as d]
            [datascript.transit :as dt]
            ["firebase/app" :as firebase]))

(def seid-key :datascript-firebase/seid)
(def seid-schema {seid-key {:db/unique :db.unique/identity
                            :db/index true}})

(defn- server-timestamp []
  (.serverTimestamp (.-FieldValue (.-firestore firebase))))

(defn- new-seid [link]
  ; Note: this doesn't actually create a doc.
  (.-id (.doc (.collection (.firestore firebase) (:path link)))))

(defn- datom->op [datom]
  [(if (pos? (:tx datom))
     :db/add
     :db/retract)
   (:e datom) (:a datom) (:v datom)])

(defn- resolve-id [id local global]
  (or (get local id)
      (get global id)))

(defn- throw-unresolved-id [id]
  (if id
    id
    (throw (str "Could not resolve eid " id))))

(defn- resolve-op [op refs local global]
  [(op 0)
   (throw-unresolved-id (resolve-id (op 1) local global))
   (op 2)
   (if (contains? refs (op 2))
     (throw-unresolved-id (resolve-id (op 3) local global))
     (op 3))])

; TODO:
; - add serverside timestamp
; - add fb index (if needed? it might be auto)
; - add docs that this returns a promise with the doc (and thus seid)
(defn- save-to-firestore! [link tx-data]
  (.add (.collection (.firestore firebase) (:path link)) 
        #js {:t (dt/write-transit-str tx-data)
             :ts (server-timestamp)}))

(defn- transact-to-datascript! [link ops seid->tempid]
  (let [tempids (dissoc (:tempids (d/transact! (:conn link) ops)) :db/current-tx)]
    (doseq [entry seid->tempid]
      (let [seid (key entry)
            eid (get tempids (val entry))]
        (swap! (:seid->eid link) assoc seid eid)
        (swap! (:eid->seid link) assoc eid seid)))))

; circle back on the firestore-transact! to look up seid refs
(defn- load-transaction! [link tx-data]
  (let [refs (:db.type/ref (:rschema @(:conn link)))]
    (loop [input-ops tx-data
           output-ops []
           seid->tempid {}
           max-tempid 0]
      (if (empty? input-ops)
        (transact-to-datascript! link output-ops seid->tempid)
        (let [op (first input-ops)
              seid (op 1)
              existing-eid (resolve-id seid seid->tempid @(:seid->eid link))
              new-max-tempid (if existing-eid max-tempid (inc max-tempid))
              new-seid->tempid (if existing-eid
                                 seid->tempid
                                 (assoc seid->tempid seid (- new-max-tempid)))]
          (recur (rest input-ops)
                 (conj output-ops (resolve-op op refs new-seid->tempid @(:seid->eid link)))
                 new-seid->tempid
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
; - caveat, no ref to non-existing entity
; - after I have tests, check if it's ok to just leave a tempid on new entities
; - test multiple tempids, including refs
(defn save-transaction! [link tx]
  (let [report (d/with @(:conn link) tx)
        refs (:db.type/ref (:rschema @(:conn link)))]
    (loop [tx-data (:tx-data report)
           ops []
           eid->seid (into {} (map #(vector (val %) (new-seid link))
                                   (dissoc (:tempids report) :db/current-tx)))]
      (if (empty? tx-data)
        (save-to-firestore! link ops)
        (let [op (datom->op (first tx-data))
              eid (op 1)
              new-eid->seid (if (resolve-id eid eid->seid @(:eid->seid link))
                              eid->seid
                              (assoc eid->seid eid (new-seid link)))]
          (recur (rest tx-data)
                 (conj ops (resolve-op op refs new-eid->seid @(:eid->seid link)))
                 new-eid->seid))))))

; change this name to something less confusing
(defn create-link
  [conn path]
  (with-meta
    {:conn conn
     :path path
     :known-stx (atom #{})
      ; move these maps onto the connection?
      ; pros:
      ; - not on fb (but that can be done either way)
      ; - not taking up space on ds
      ; - don't need custom schema
      ; - can make this conn a big atom instead of having many atoms inside
      ; - can just populate existing ds connection (that makes enough sense because of the 
      ;   separate save-transaction!)
      ; - can still do it later and optionally on load-transaction!
      ; cons:
      ; - less meta
      ; - can't move ds-conn around to another fb-conn 
      ;   - but could with an option to do it on load
      ;   - but move around anyway because the known-stx are local to the link and would
      ;     be duplicated
     :seid->eid (atom {})
     :eid->seid (atom {})}
    {:unsubscribe (atom nil)}))

(defn unlisten! [link]
  (let [unsubscribe @(:unsubscribe (meta link))]
    (when unsubscribe (unsubscribe))
    (reset! (:unsubscribe (meta link)) nil)))

(defn listen! 
  ([link] (listen! link js/undefined))
  ([link error-cb] 
   (unlisten! link)
   (reset! (:unsubscribe (meta link))
           (.onSnapshot (.orderBy (.collection (.firestore firebase) (:path link)) "ts")
                        (fn [snapshot]
                          (.forEach (.docChanges snapshot)
                                    #(let [data (.data (.-doc %))
                                           id (.-id (.-doc %))]
                                       (when (and (= (.-type %) "added")
                                                  (not (contains? @(:known-stx link) id)))
                                         (load-transaction! link (dt/read-transit-str (.-t data)))
                                         (swap! (:known-stx link) conj id)))))
                        error-cb))))


