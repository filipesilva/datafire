(ns app.cards
            ; devcards needs cljsjs.react and cljsjs.react.dom to be imported
            ; separately for shadow-cljs to add shims.
  (:require [cljsjs.react]
            [cljsjs.react.dom]
            ; We shouldn't need to load reagent directly, but it looks like devcards
            ; is importing it in such a way that it needs to be imported beforehand.
            [reagent.core]
            [devcards.core :refer [start-devcard-ui!]]
            ; Import all namespaces with cards here to load them.
            [app.sandbox]
            [app.tests]
            [app.offline-tests]))

; 15x the usual devcards timeout to give time for the sync tests.
(set! devcards.core/test-timeout 12000)

(defn ^:export main
  "Start the devcards UI."
  []
  ; Add a special class to the body to signal we're in devcards mode.
  ; We want to mostly use the same styles as the app, but might need to make
  ; some exceptions.
  (js/document.body.classList.add "using-devcards")
  ; Start the devcards UI.
  (start-devcard-ui!))
