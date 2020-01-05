(ns datascript-firebase.core
  (:require [cljs.core.async :refer [<! chan close! go go-loop put!]]
            [async-interop.interop :refer [<p!]]
            [datascript.core :as d]
            [datascript.transit :as dt]
            ["firebase/app" :as firebase]
            ["firebase/firestore"]))

(def ^:private default-firebase-app "[DEFAULT]")

(defn- firestore [link]
  (.firestore (.app firebase (:name link))))

(defn- server-timestamp []
  (.serverTimestamp (.-FieldValue (.-firestore firebase))))

(defn db-coll [link]
  (.collection (firestore link) (:path link)))

(defn logs-coll [link]
  (.collection (.doc (db-coll link) "log") "logs"))

(defn meta-doc [link]
  (.doc (db-coll link) "metadata"))

(defn- new-seid [link]
  ; Note: this doesn't actually create a doc.
  (.-id (.doc (.collection (firestore link) (:path link)))))

(defn- datom->op [datom]
  [(if (pos? (:tx datom))
     :db/add
     :db/retract)
   (:e datom) (:a datom) (:v datom)])

(defn- resolve-id [id local global]
  (or (get local id)
      (get global id)))

(defn- throw-unresolved-id [id local global]
  (if-let [resolved (resolve-id id local global)]
    resolved
    (throw (str "Could not resolve eid " id))))

(defn- resolve-op [op refs local global]
  [(op 0)
   (throw-unresolved-id (op 1) local global)
   (op 2)
   (if (contains? refs (op 2))
     (throw-unresolved-id (op 3) local global)
     (op 3))])

(defn- save-to-firestore! [link tx-data]
  (let [coll (logs-coll link)
        granularity (:granularity link)]
    (cond (= granularity :tx) (.add coll #js {:t (dt/write-transit-str tx-data)
                                              :ts (server-timestamp)})
          ; Firestore transactions can't be done offline, but batches can so we use that.
          (= granularity :datom) (let [batch (.batch (firestore link))
                                       tx (.doc coll)
                                       datoms-coll (.collection tx "d")]
                                   (doseq [[idx op] (map-indexed vector tx-data)]
                                     (.set batch (.doc datoms-coll)
                                           #js {:i idx
                                                :e (op 1)
                                                :d (dt/write-transit-str op)}))
                                  ;  Note: the tx doc always needs to have a field, otherwise
                                  ;  watching it won't show it was added.
                                   (.set batch tx #js {:ts (server-timestamp)})
                                   (.commit batch))
          :else (throw (str "Unsupported granularity: " granularity)))))

(defn- transact-to-datascript! [link ops seid->tempid]
  (let [tempids (dissoc (:tempids (d/transact! (:conn link) ops)) :db/current-tx)]
    (doseq [entry seid->tempid]
      (let [seid (key entry)
            eid (get tempids (val entry))]
        (swap! (:seid->eid link) assoc seid eid)
        (swap! (:eid->seid link) assoc eid seid)))))

(defn- update-tempids [op refs max-tempid seid->tempid seid->eid]
  (loop [seids (if (contains? refs (op 2))
                 [(op 1) (op 3)]
                 [(op 1)])
         seid->tempid seid->tempid
         max-tempid max-tempid]
    (if (empty? seids)
      [seid->tempid max-tempid]
      (let [seid (first seids)
            existing-eid (resolve-id seid seid->tempid seid->eid)
            new-max-tempid (if existing-eid max-tempid (inc max-tempid))
            new-seid->tempid (if existing-eid
                               seid->tempid
                               (assoc seid->tempid seid (- new-max-tempid)))]
        (recur (rest seids)
               new-seid->tempid
               new-max-tempid)))))

(defn- load-transaction! [link tx-data]
  (let [refs (:db.type/ref (:rschema @(:conn link)))
        seid->eid @(:seid->eid link)]
    (loop [input-ops tx-data
           output-ops []
           seid->tempid {}
           max-tempid 0]
      (if (empty? input-ops)
        (transact-to-datascript! link output-ops seid->tempid)
        (let [op (first input-ops)
              [new-seid->tempid
               new-max-tempid] (update-tempids op refs max-tempid seid->tempid seid->eid)]
          (recur (rest input-ops)
                 (conj output-ops (resolve-op op refs new-seid->tempid seid->eid))
                 new-seid->tempid
                 new-max-tempid))))))

; Loading each tx separately is very slow and completely breaks the responsiveness.
; To do datom granularity right, they need to all be in the same collection.
(defn- load-stx-datoms [link id]
  (go
    (let [tx-coll (.collection (firestore link) (:path link))
          tx-doc (.doc tx-coll id)
          datom-coll (.orderBy (.collection tx-doc "d") "i")
          datoms (js->clj (.map (.-docs (<p! (.get datom-coll))) #(.-d (.data %))))
          tx-data (map #(dt/read-transit-str %) datoms)]
      tx-data)))

(defn- load-doc [link [id data]]
  (go
    (let [granularity (:granularity link)
          tx-data (cond (= granularity :tx) (dt/read-transit-str (.-t data))
                        (= granularity :datom) (<! (load-stx-datoms link id))
                        :else (throw (str "Unsupported granularity: " granularity)))]
      (load-transaction! link tx-data))
    (swap! (:known-stx link) conj id)))

(defn- listen-to-firestore [link error-cb c]
  (.onSnapshot (.orderBy (logs-coll link) "ts")
               (fn [snapshot]
                 (.forEach (.docChanges snapshot)
                           #(let [data (.data (.-doc %))
                                  id (.-id (.-doc %))]
                              ; Only listen to "added" events because our transactions are 
                              ; immutable on the server.
                              ; The server timestamp is technically an exception, since the client
                              ; that adds the transaction will see a "modified" event when the
                              ; timestamp is added, but other clients will only see the "added".
                              ; This isn't a problem because the timestamp is used for ordering and
                              ; we assume client tx happen as soon as they are committed locally.
                              (when (and (= (.-type %) "added")
                                         (not (contains? @(:known-stx link) id)))
                                ; Put doc into channel. 
                                ; Only do sync computation here to ensure docs are put into channel
                                ; in order.
                                (put! c [id data])))))
               error-cb))

(defn transact! [link tx]
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

(defn create-link
  ([conn path] (create-link conn path default-firebase-app))
  ([conn path name]
   (with-meta
     {:conn conn
      :path path
      :name name
      :granularity :tx
      ; :granularity :datom
      :known-stx (atom #{})
      :seid->eid (atom {})
      :eid->seid (atom {})}
     {:unsubscribe (atom nil)
      :chan (atom nil)})))

(defn unlisten! [link]
  (let [unsubscribe @(:unsubscribe (meta link))
        c @(:chan (meta link))]
    (when unsubscribe (unsubscribe))
    (when c (close! c))
    (reset! (:chan (meta link)) nil)
    (reset! (:unsubscribe (meta link)) nil)))

(defn listen!
  ([link] (listen! link js/undefined))
  ([link error-cb]
   (unlisten! link)
   (let [c (chan)]
     (reset! (:chan (meta link)) c)
     (reset! (:unsubscribe (meta link)) (listen-to-firestore link error-cb c))
     (go-loop [doc (<! c)]
       (when-not (nil? doc)
         (<! (load-doc link doc))
         (recur (<! c)))))))