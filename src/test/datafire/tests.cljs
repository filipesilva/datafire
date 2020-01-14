(ns datafire.tests
  (:require [cljs.test :refer [is async]]
            [cljs.core.async :refer [go <!]]
            [async-interop.interop :refer [<p!]]
            [devcards.core :refer [deftest]]
            [datafire.core :as df]
            [datafire.samples :refer [data schema]]
            [datafire.test-helpers :refer [test-link pull-lethal-weapon pulled-lethal-weapon-snapshot]]))

(defn saves-transactions [done granularity]
  (go (let [[conn link] (<! (test-link {:schema schema :granularity granularity}))]
        (<p! (df/transact! link data))
        (is (= (pull-lethal-weapon conn) pulled-lethal-weapon-snapshot))
        (done))))

(defn syncs-transactions [done granularity]
  (go (let [[_ link path name] (<! (test-link {:schema schema :granularity granularity}))
            [conn] (<! (test-link {:schema schema :path path :name name :granularity granularity}))]
        (<p! (df/transact! link data))
        (is (= (pull-lethal-weapon conn) pulled-lethal-weapon-snapshot))
        (done))))

(deftest saves-transactions-tx
  (async done (saves-transactions done :tx)))

(deftest saves-transactions-datom
  (async done (saves-transactions done :datom)))

(deftest syncs-transactions-tx
  (async done (syncs-transactions done :tx)))

(deftest syncs-transactions-datom
  (async done (syncs-transactions done :datom)))
