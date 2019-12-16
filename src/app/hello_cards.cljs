(ns app.hello-cards
  ; We shouldn't need to load reagent directly, but it looks like devcards
  ; is importing it in such a way that it needs to be imported beforehand.
  (:require [reagent.core]
            [devcards.core :refer [defcard defcard-rg]]
            [app.hello :refer [add-ada]]
            [app.db :refer [fb-tx-atom datascript-connection]]))

(defcard-rg add-ada-card
  add-ada)

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

(defcard datascript-connection-card
  datascript-connection)

(defcard firestore-tx
    (fb-tx-atom) [] {:history false})
