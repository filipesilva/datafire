(ns app.tests
  (:require [cljs.test :refer [is async]]
            [cljs.core.async :refer [go]]
            [async-interop.interop :refer [<p!]]
            [devcards.core :refer [deftest]]
            [datascript-firebase.core :as df]
            [app.samples :refer [data schema]]
            [app.test-helpers :refer [test-link pull-lethal-weapon pulled-lethal-weapon-snapshot]]))

(deftest saves-transactions
  (async done
         (go
           (let [[conn link] (test-link schema)]
             (<p! (df/transact! link data))
             (is (= (pull-lethal-weapon conn) pulled-lethal-weapon-snapshot))
             (done)))))

(deftest syncs-transactions
  (async done
         (go
           (let [[_ link path name] (test-link schema)
                 [conn] (test-link schema path name)]
             (<p! (df/transact! link data))
             (is (= (pull-lethal-weapon conn) pulled-lethal-weapon-snapshot))
             (done)))))