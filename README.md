# Phi
A [ClojureScript](https://github.com/clojure/clojurescript) framework
built on [React.js](https://facebook.github.io/react/) and based on
the principles of [Flux](https://facebook.github.io/flux/).

Before I dive into the details of Phi, I'd like to acknowledge the
following projects and their maintainers. Their work heavily influenced this
project, and without them Phi wouldn't exist:

* [DataScript Chat](https://github.com/tonsky/datascript-chat)
* [Om](https://github.com/swannodette/om/)

## Why Phi
The underlying philosophy of Phi is that:

1. All state should be global and globally available
2. All updates to state should be done by passing a message
3. All messages should be passed over a global channel and dispatched to the appropriate handlers

Therefore, the "flow" of an app is something like this:

```
|--->Db---->Render---->Message-->|
|                                |
|                                |
|<--Update Db<--------Dispatch<--|
```

Decoupling state updates from other concerns means that the source of an update event is
irrelevant. A message can come from the user, an http call, a push event, etc. They are
all handled in a uniform manner.

Sending a message for those updates provides natural hooks for extending functionality.
For example, suppose that for certain events you want to log something, or you want to
make a remote call, or you want to make an unrelated state update. Any and all of these
things can be done without affecting current functionality by adding subscribers to the
desired event type.

## Hi Phi
Here's a small example application in Phi:

```clojure
(ns simple-app
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :as a :refer [<! chan]]
            [phi.core :as phi :refer [conn component publish! event add-subscriber]]))

(phi/init-associative-conn! (atom {:message ""}))

(add-subscriber update-message (a/sliding-buffer 20) [:update-message]
  (go-loop []
    (when-some [{{:keys [new-message]} :message} (<! update-message)]
      (swap! conn assoc :message new-message)
      (recur))))

(def display-message
  (component
    (reify
      phi/IPhi
      (render [_ db {:keys [static-message]}]
        (let [text (:message db)]
          [:div
           [:div (str "static message: " static-message)]
           [:div (str "dynamic message: " text)]])))))

(def message-input
  (component
    (reify
      phi/IPhi
      (render [_ db]
        (let [text (:message db)]
          [:div
           [:input
            {:value text
             :on-change
             (fn [e]
               (.preventDefault e)
               (publish!
                 (event :update-message {:new-message (.. e -target -value)})))}]])))))

(def simple-app
  (component
    (reify
      phi/IPhi
      (render [_ db]
        [:div
         (display-message db {:static-message "Hello, Phi!"})
         (message-input db)]))))

(phi/mount-app simple-app (.-body js/document))
```

## Phi vs. [Om](https://github.com/swannodette/om/)
Phi is based on Om, so there are a lot of similarities. They both
use a global application state, and they both make heavy use of protocols.

At the end of the day, the only fundamental difference between Om and Phi
is that with Phi you pass the entire application state to components, and with Om you
pass parts of the state to components with cursors. Everything else Phi does
can be done in Om. Om just doesn't provide it out of the box.

Which begs the question.

### Why no cursors?
As I understand it, cursors provide two things: modularity and speed. Regarding the former,
I disagree that modularity is a worthwhile goal. I see no value whatsoever in forbidding others
from viewing "my piece" of the global state. It is, after all, global. And as long as everyone
makes valid transactions, there is little value in forbidding others from writing to "my piece"
of global state.

Regarding speed, speed is always valuable. However, as with all things, there is a trade-off
to using cursors. They limit your view of the world, perhaps prematurely. Making changes when
you don't have the full state available is more difficult.

Phi does support passing parts of state to subcomponents via `IPhiProps`, and those props will
be used in `shouldComponentUpdate` but it should should be seen as an optimization tradeoff.

### Short list of differences
Phi:
* All components can query the entirety of the application state
* No intermediary between you and your state (i.e. no cursors)
* State hiding, if that's what you're in to
* Built-in hiccup style templates
* Built-in event system
* Built-in integration with [DataScript](LKJLJ)

Om:
* Faster, due to its state management
* State hiding, if that's what you're in to
* No built-in templating system, meaning you can use what you want
