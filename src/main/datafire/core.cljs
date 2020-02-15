(ns datafire.core
  (:require [datascript.core :as d]
            [datascript.transit :as dt]
            ["firebase/app" :as firebase]
            ["firebase/firestore"]))

(def default-firebase-app "[DEFAULT]")

(defn- firestore [link]
  (.firestore (.app firebase (:name link))))

(defn- server-timestamp []
  (.serverTimestamp (.-FieldValue (.-firestore firebase))))

(defn db [link]
  (.doc (firestore link) (:path link)))

(defn txs [link]
  (.collection (db link) "txs"))

(defn- new-seid [link]
  ; Note: this doesn't actually create a doc.
  (.-id (.doc (txs link))))

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
  (let [coll (txs link)
        granularity (:granularity link)]
    (cond (= granularity :tx) (.add coll #js {:t (dt/write-transit-str tx-data)
                                              :ts (server-timestamp)})
          ; Firestore transactions can't be done offline, but batches can so we use that.
          (= granularity :datom) (let [batch (.batch (firestore link))
                                       tx-id (.-id (.doc coll))]
                                   (doseq [[idx op] (map-indexed vector tx-data)]
                                     (.set batch (.doc coll)
                                           #js {:tx tx-id
                                                :ts (server-timestamp)
                                                ; Order matters in DS, so we keep it.
                                                ; https://github.com/tonsky/datascript/issues/172
                                                :i idx
                                                :d (dt/write-transit-str op)}))
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

(defn- snapshot->txs [link snapshot]
  (let [granularity (:granularity link)
        ; Only listen to "added" events because our transactions are 
        ; immutable on the server.
        ; The server timestamp is technically an exception, since the client
        ; that adds the transaction will see a "modified" event when the
        ; timestamp is added, but other clients will only see the "added".
        ; This isn't a problem because the timestamp is used for ordering and
        ; we assume client tx happen as soon as they are committed locally.        
        doc-changes (.filter (.docChanges snapshot) #(= (.-type %) "added"))
        length (.-length doc-changes)]
    ; On tx granularity, each doc is a transaction.
    (cond (= granularity :tx) (loop [idx 0
                                     txs []]
                                (if (= idx length)
                                  txs
                                  (recur (inc idx)
                                         (conj txs
                                               (dt/read-transit-str
                                                (.-t (.data (.-doc (aget doc-changes idx)))))))))
          ; On datom granularity, each doc is a datom that belongs to a given transaction.
          (= granularity :datom) (loop [idx 0
                                        tx-ids []
                                        txs-map {}]
                                   (if (= idx length)
                                     (map #(vals (get txs-map %)) tx-ids)
                                     (let [data (.data (.-doc (aget doc-changes idx)))
                                           datom (dt/read-transit-str (.-d data))
                                           tx-id (.-tx data)
                                           tx-idx (.-i data)]
                                       (if (contains? txs-map tx-id)
                                         (recur (inc idx)
                                                tx-ids
                                                (update txs-map tx-id conj [tx-idx datom]))
                                         (recur (inc idx)
                                                (conj tx-ids tx-id)
                                                (conj txs-map
                                                      [tx-id (sorted-map tx-idx datom)]))))))
          :else (throw (str "Unsupported granularity: " granularity)))))

(defn- listen-to-firestore [link error-cb]
  ; Any given snapshot contains full transactions regardless of granularity.
  ; With :tx granularity, that's a single doc.
  ; With :datom granularity, there's a doc for each datom in the tx, but they are in the same
  ; snapshot because the writes are batched.
  (.onSnapshot (.orderBy (txs link) "ts")
               #(doseq [tx-data (snapshot->txs link %)]
                  (load-transaction! link tx-data))
               error-cb))

(defn transact!
  "Persist tx-data on the link.
   Returns a promise that resolves when the transaction hits the server.
   Since the promise won't resolve while offline, it's recommended that you never wait for it."
  [link tx-data]
  (let [report (d/with @(:conn link) tx-data)
        eid->seid (into {} (map #(vector (val %) (new-seid link))
                                (dissoc (:tempids report) :db/current-tx)))
        resolved-ops (map #(resolve-op (datom->op %)
                                       (:db.type/ref (:rschema @(:conn link)))
                                       eid->seid
                                       @(:eid->seid link)) 
                          (:tx-data report))]
    (save-to-firestore! link resolved-ops)))

(defn create-link
  "Create a link between a Datascript connection and a Firestore document path."
  ([conn path] (create-link conn path {}))
  ([conn path {:keys [name granularity]
               :or {name default-firebase-app
                    granularity :tx}}]
   (with-meta
     {:conn conn
      :path path
      :name name
      :granularity granularity
      :seid->eid (atom {})
      :eid->seid (atom {})}
     {:unsubscribe (atom nil)})))

(defn unlisten!
  "Stop listening to transactions on Firebase."
  [link]
  (let [unsubscribe @(:unsubscribe (meta link))]
    (when unsubscribe (unsubscribe))
    (reset! (:unsubscribe (meta link)) nil)))

(defn listen!
  "Start listening to transactions on the link and applies them to the Datascript connection.
   Previous transactions will be loaded onto the Datascript connection."
  ([link] (listen! link js/undefined))
  ([link error-cb]
   (unlisten! link)
   (reset! (:unsubscribe (meta link)) (listen-to-firestore link error-cb))))
