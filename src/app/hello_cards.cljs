(ns app.hello-cards
  ; We shouldn't need to load reagent directly, but it looks like devcards
  ; is importing it in such a way that it needs to be imported beforehand.
  (:require [reagent.core]
            [devcards.core :refer [defcard defcard-rg]]
            [app.hello :refer [add-ada]]
            [app.projections :refer [ds->seq]]
            [app.db :refer [fb-user-atom datascript-connection]]))

(defcard-rg add-ada-card
  add-ada)

(defcard firestore-user-collection-card
  (fb-user-atom) [] {:history false})

(defcard-rg datascript-connection-card
  [:div]
  datascript-connection
  {:inspect-data true
   :history      false
   :projection   ds->seq})