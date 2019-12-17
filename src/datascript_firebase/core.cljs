(ns datascript-firebase.core
  (:require [datascript.core :as d]
            [datascript.transit :as dt]
            ["firebase/app" :as firebase]))

(def seid-key :datascript-firebase/seid)
(def seid-schema {seid-key {:db/unique :db.unique/identity
                            :db/index true}})

(defn- new-seid [link]
  ; Note: this doesn't actually create a doc.
  (.-id (.doc (.collection (.firestore firebase) (:path link)))))

(defn- datom->op [datom]
  [(if (pos? (:tx datom))
     :db/add
     :db/retract)
   (:e datom) (:a datom) (:v datom)])

; TODO:
; - add serverside timestamp
; - add fb index (if needed? it might be auto)
; - add docs that this returns a promise with the doc (and thus seid)
(defn- save-to-firestore! [link tx-data]
  (.add (.collection (.firestore firebase) (:path link)) 
        #js {:t (dt/write-transit-str tx-data)}))

; circle back on the firestore-transact! to look up seid refs
(defn- load-transaction! [link tx-data]
  (loop [input-ops tx-data
         output-ops []
         seid->tempid {}
         max-tempid 0]
    (if (empty? input-ops)
      (d/transact! (:conn link) output-ops)
      (let [op (first input-ops)
            fb-eid (op 1)
            seid (and (= seid-key (op 2)) (op 3))
            new-max-tempid (if seid (dec max-tempid) max-tempid)
            new-seid->temp-id (if seid
                                (assoc seid->tempid seid new-max-tempid)
                                seid->tempid)
            ds-eid (or (get new-seid->temp-id fb-eid)
                       (:db/id (d/entity @(:conn link) [seid-key fb-eid])))
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
; - caveat, no ref to non-existing entity
; - after I have tests, check if it's ok to just leave a tempid on new entities
(defn save-transaction! [link tx]
  (let [report (d/with @(:conn link) tx)
        tempids (dissoc (:tempids report) :db/current-tx)]
    (print tempids)
    ; seed initial ops and eid->seid from tempids
    (loop [tx-data (:tx-data report)
           ops []
           eid->seid {}]
      (if (empty? tx-data)
        (save-to-firestore! link ops)
        (let [datom (first tx-data)
              eid (:e datom)
              new? (not (contains? eid->seid eid))
              seid (get eid->seid (:e datom) (new-seid link))
              new-ops (conj ops (datom->op (assoc datom :e seid)))]
          ; on datom->op have to check if :a is a ref
          ; lookup seid for ref on 
          ; error out if ref but no seid
          (recur (rest tx-data)
                 ; Prepend seid ops, append others. 
                 ; This way the seid op will always be loaded first.
                 (if new?
                   (vec (concat [[:db/add seid seid-key seid]] new-ops))
                   new-ops)
                 (if new?
                   (assoc eid->seid eid seid)
                   eid->seid)))))))

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
     :eid->eid (atom {})}
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
           (.onSnapshot (.collection (.firestore firebase) (:path link))
                        (fn [snapshot]
                          (.forEach (.docChanges snapshot)
                                    #(let [data (.data (.-doc %))
                                           id (.-id (.-doc %))]
                                       (when (and (= (.-type %) "added")
                                                  (not (contains? @(:known-stx link) id)))
                                         (load-transaction! link (dt/read-transit-str (.-t data)))
                                         (swap! (:known-stx link) conj id)))))
                        error-cb))))