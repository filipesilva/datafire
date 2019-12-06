(ns app.hello-cards
  (:require [devcards.core :as dc :refer [defcard defcard-rg]]
            [app.hello :refer [add-ada]]
            [app.projections :refer [ds->seq]]
            [app.db :refer [fb-user-atom datascript-connection]]))

(defcard add-ada-card
  (dc/reagent add-ada))

(defcard firestore-user-collection-card
  (fb-user-atom) [] {:history false})

(defcard-rg datascript-connection-card
  [:div]
  datascript-connection
  {:inspect-data true
   :history      false
   :projection   ds->seq})