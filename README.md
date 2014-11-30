# Phi
A [ClojureScript](https://github.com/clojure/clojurescript) framework
built on [React.js](https://facebook.github.io/react/) and based on
the principles of [Flux](https://facebook.github.io/flux/).

Before I dive into the details of Phi, I'd like to acknowledge the
following projects and their maintainers. Their work heavily influenced this
project, and without them Phi wouldn't exist:

* [DataScript Chat](https://github.com/tonsky/datascript-chat)
* [Om](https://github.com/swannodette/om/)

## Current Version
```
[phi "0.4.0"]
```

## Why Phi
The underlying philosophy of Phi is that:

1. All state should be global and globally available
2. All updates to state should be done by passing a message
3. All messages should be passed over a global channel and dispatched to the appropriate handlers

Therefore, the "flow" of an app is something like this:

```
^--->Realize Db---->Render---->Message-->v
|                                        |
|                                        |
^<------Update Db<---------Dispatch<-----v
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
Before getting started there are a few concepts to go over:
* `conn`: A `cljs.core/atom` containing your db
* `db`: An dereferenced immutable structure containing all of your application state
* `event`: A record containing an id, type, and message
* `component`: Corresponds to `React.createClass`

With that out of the way, here's a small hello world application in Phi.

First create your `project.clj`:

```
(defproject
  hello-world "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [phi "0.4.0"]]
  :plugins [[lein-cljsbuild "1.0.3"]]
  :cljsbuild {:builds
              [{:id "dev"
                :source-paths ["src"]
                :compiler {:optimizations :whitespace
                           :pretty-print true
                           :preamble ["react/react.js"]
                           :output-to "target/hello-world.js"}}]})
```

In `index.html`:

```html
<html>
  <body>
    <script src="target/hello-world.js" type="text/javascript"></script>
  </body>
</html>
```

Now the goods. In `src/hello_word.cljs`:

```clojure
(ns hello-world
  (:require [cljs.core.async :as a]
            [phi.core :as phi]))

(phi/init-conn! (atom {:message ""}))
(phi/start-debug-conn!)
(phi/start-debug-events!)

(phi/routing-table
  (a/sliding-buffer 10)
  [[::update-message] (fn update-message [{{:keys [new-message]} :message}]
                        (swap! phi/conn assoc :message new-message))])

(def display-message
  (phi/component
    (reify
      phi/IPhi
      (render [_ {:keys [static-message]} db]
        (let [text (:message db)]
          [:div
           [:div (str "static message: " static-message)]
           [:div (str "dynamic message: " text)]])))))

(def message-input
  (phi/component
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
               (phi/publish! ::update-message {:new-message (.. e -target -value)}))}]])))))

(def simple-app
  (phi/component
    (reify
      phi/IPhi
      (render [_ db]
        [:div
         (display-message db {:static-message "Hello, Phi!"})
         (message-input db)]))))

(phi/mount-app simple-app (.-body js/document))
```

You can build this example with:
```
lein do cljsbuild clean, cljsbuild once dev

# or, to rebuild automatically

lein do cljsbuild clean, cljsbuild auto dev
```

Then just open `index.html` to see your application at work.

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
I disagree that modularity is a worthwhile goal. I see no value in forbidding others from
viewing "my piece" of the global state. It is, after all, global. And as long as everyone
makes valid transactions, there is little value in forbidding others from writing to "my piece"
of global state.

Regarding speed, speed is always valuable. However, as with all things, there is a trade-off
to using cursors. They limit your view of the world, perhaps prematurely. Making changes when
you don't have the full state available is more difficult.

Phi does support passing parts of state to subcomponents via `IPhiProps`, and those props will
be used in `shouldComponentUpdate`, but it should be seen as an optimization tradeoff.

### Short list of differences
Phi:
* All components can query the entirety of the application state
* No intermediary between you and your state (i.e. no cursors)
* State hiding, if that's what you're in to
* Built-in hiccup style templates
* Built-in event management system
* Built-in integration with [DataScript](https://github.com/tonsky/datascript)

Om:
* Generally faster
* State hiding, if that's what you're in to
* No built-in templating system, meaning you can use what you want
