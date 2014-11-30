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
[phi "0.5.0"]
```

## Table Of Contents
- [Phi](#phi)
- [Current Version](#current-version)
- [Why Phi](#why-phi)
- [Hi Phi - An Introduction](#hi-phi)
- [API](#api)
  - [State](#state)
    - [`conn`](#conn)
    - [`db`](#db)
    - [`phi.core/conn`](#phicoreconn)
    - [`init-conn!`](#init-conn)
    - [Db Data Structures](#db-data-structures)
  - [Events](#events)
    - [Global Publication](#global-publication)
      - [`publish!](#publish)
      - [`publisher-mult`](#publisher-mult)
    - [`event`](#event)
    - [Subscribers](#subscribers)
      - [`subscribers-map`](#subscribers-map)
      - [`add-subscriber`](#add-subscriber)
      - [`routing-table`](#routing-table)
  - [Rendering](#rendering)
    - [`component`](#component)
      - [Phi Protocols](#phi-protocols)
        - [`IPhi`](#iphi)
        - [`IPhiProps`](#iphiprops)
        - [`ISubscribe`](#isubscribe)
      - [Lifecycle Protocols](#lifecycle-protocols)
        - [`IDisplayName`](#idisplayname)
        - [`IShouldUpdate`](#ishouldupdate)
        - [`IShouldUpdateForProps`](#ishouldupdateforprops)
        - [`IWillMount`](#iwillmount)
        - [`IDidMount`](#ididmount)
        - [`IWillUnmount`](#iwillunmount)
        - [`IWillUpdate`](#iwillupdate)
        - [`IWillUpdateProps`](#iwillupdateprops)
        - [`IDidUpdate`](#ididupdate)
        - [`IDidUpdateProps`](#ididupdateprops)
    - [`mount-app`](#mount-app)
    - [`unmount-app`](#unmount-app)
    - [`get-ref`](#get-ref)
    - [`get-dom-node`](#get-dom-node)
    - [`get-child-node`](#get-child-node)
    - [`mounted?`](#mounted)
  - [Debugging](#debugging)
    - [`start-debug-events!`](#start-debug-events)
    - [`stop-debug-events!`](#stop-debug-events)
    - [`start-debug-conn!`](#start-debug-conn)
    - [`stop-debug-conn!`](#stop-debug-conn)
- [Phi Examples](#phi-examples)
- [Phi vs. Om](#phi-vs-om)
  - [Why No Cursors?](#why-no-cursors)
  - [Short List of Differences](#short-list-of-differences)

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
First create your `project.clj`:

```clojure
(defproject
  hello-world "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [phi "0.5.0"]]
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
      (render [_ db {:keys [static-message]}]
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
```

Or, to rebuild automatically:

```
lein do cljsbuild clean, cljsbuild auto dev
```

Then just open `index.html` to see your application at work.

## API
### State
In its API, Phi makes a distinction between a `conn` and a `db`. (This nomenclature was taken
from [DataScript](https://github.com/tonsky/datascript).)

<a name="conn">`conn`</a> is an [atom](http://clojure.org/atoms) which contains a [`db`](#db).

<a name="db">`db`</a> is a [data structure](#db-data-structures) representing the current
state of your application.

<a name="phicoreconn">`phi.core/conn`</a> is the global reference to your app's `conn`. The
purpose of this is to put your `conn` in a predefined, global location, so you and all
of the libraries you use can easily access the `conn`.

<a name="init-conn">`(init-conn! conn)`</a> is the function which defines [`phi.core/conn`](#phicoreconn)
using `defonce`. This function must be called before mounting your app.

#### Db Data Structures
Phi has no opinion about what you should use for your db data structure. You can use
seqs, lists, maps, vectors, or even a raw JavaScript object. You can use what you think is best.

That being said, I suspect you'll have the most flexibility with either DataScript or an
associative structure (maps and vectors). My personal favorite, and the project which partially
inspired Phi, is DataScript. It is the most flexible, and has extremely powerful semantics.

### Events
Every event in Phi goes over a single, global `core.async/chan`. Events are dispatched by type
via core.async's built-in [`pub`](https://clojure.github.io/core.async/#clojure.core.async/pub).
Subscribers are channels that are subscribed via [`sub`](https://clojure.github.io/core.async/#clojure.core.async/sub).

<a name="event-type">`Event`</a> is a record consisting of an `id`, `type`, and `message`.

<a name="event">(event type message)</a> convenience constructor for `Event`s. Generates a unique id.

<a name="publish">`(publish! event)`</a> publishes an event. There is also the convenience overload
`(publish! type message)` which constructs an event and immediately publishes it.

<a name="subscribers-map">`subscribers-map`</a> is an atom containing a map of the following form:

```clojure
{:chan-key-a {:chan chan-a, :topics [:event-type-a]}
 :chan-key-b {:chan chan-b, :topics [:event-type-b]}}
```

This is used internally for cleaning up subscribers. It can also potentially be used to see what chans
are subscribed to particular topics.

<a name="add-subscriber">`add-subscriber`</a> is a macro that creates a single subscriber. Returns a
generated chan-key for retrieval from [`subscribers-map`](#subscribers-map). Example usage:

```clojure
(add-subscriber my-subscriber (a/sliding-buffer 10) [:my-event-type]
  (go-loop []
    (when-some [v (<! my-subscriber)]
      (... do stuff)
      (recur))
```

This is useful when you need to do a lot of channel coordination in a subscriber.

<a name="routing-table">`routing-table`</a> is a function designed to take some of the verbosity
out of making subscribers. It has two arities. The two-arity form takes a `buf-or-n` to be used
for every subscriber. The one-arity form allows you to define a `buf-or-n` for each subscriber
individually.

Two arity example:
```clojure
(routing-table
  (a/sliding-buffer 10)
  [[:event-type-a] callback-a
   [:event-type-b] callback-b])
```
One arity example:
```clojure
(routing-table
  [[:event-type-a] (a/sliding-buffer 10) callback-a
   [:event-type-b] (a/sliding-buffer 10) callback-b])
```

Callbacks should take a single argument which will be the [`Event`](#event-type).

It returns a list of chan-keys for retrieval from the [`subscribers-map`](#subscribers-map).

<a name="publisher-mult">`publisher-mult`</a> is a [`mult`](https://clojure.github.io/core.async/#clojure.core.async/mult)
which feeds the global publication. This means that you can receive every event by tapping
this mult without affecting other subscribers. This is particularly useful for [debugging](#debugging).

### Rendering
<a name="component">`component`</a> corresponds to `React.createClass`. Creates a class and returns
a constructor. If your component implements [`IPhi`](#iphi), the constructor has the signature
`(fn [db & [props]] …)`. If it implements [`IPhiProps`](#iphiprops), it has the signature
`(fn [props] …)`. Note that the arguments are the same as the arguments passed to the `render` function
for each protocol.

The return value of `render` and `render-props` is passed through [ŜABLONO](https://github.com/r0man/sablono)'s
`sablano.core/html` macro.

Example usage:
```clojure
(def phi-props
  (phi/component
    (reify
      phi/IPhiProps
      (render-props [_ {:keys [message]}]
        (let [text (:message db)]
          [:div
           [:div (str "props message: " message)]])))))

(def regular-phi-with-props
  (phi/component
    (reify
      phi/IPhi
      (render [_ db {:keys [message]}]
        (let [text (:message db)]
          [:div
           [:div (str "props message: " message)]
           [:div (str "db message: " text)]])))))

(def regular-phi
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
         (regular-phi db)
         (regular-phi-with-props db {:message "Hello, Phi!"})
         (phi-props {:message "I have no db access"})]))))
```

##### Phi Protocols
###### `IPhi`
```clojure
(defprotocol IPhi (render [this db]
                          [this db props]))
```

This is the standard protocol for Phi components. Components can take optional props,
which is useful for passing arguments to subcomponents.

Returns a hiccup-like data structure to be parsed by [ŜABLONO](https://github.com/r0man/sablono).

###### `IPhiProps`
```clojure
(defprotocol IPhiProps (render-props [this props]))
```

This protocol is useful for optimizing an application. Components will check whether
their props changed during
[`shouldComponentUpdate`](https://facebook.github.io/react/docs/component-specs.html#updating-shouldcomponentupdate)
much like Om.

In this instance, the trade-off for speed is flexibility. Components that receive the
whole db can be easily changed to display different data. This is less true for components
that only receive specific props.

Returns a hiccup-like data structure to be parsed by [ŜABLONO](https://github.com/r0man/sablono).

###### `ISubscribe`
```clojure
(defprotocol ISubscribe (init-subscribers [this]
                                          [this props]))
```

Called once during [`componentDidMount`](https://facebook.github.io/react/docs/component-specs.html#mounting-componentdidmount).
Useful for external libraries that need to define subscribers. I don't recommend using it for
all of your subscribers.

Returns a list of [chan-keys](#subscribers-map). Those keys will be used for unsubscribing those chans
during the [`componentWillUnmount`](https://facebook.github.io/react/docs/component-specs.html#unmounting-componentwillunmount)
phase.

NOTICE: If you use this with `add-subscriber` you need to wrap all your calls in a vector so all
of the keys get returned, not just the last call.

Example using [`add-subscriber`](#add-subscriber):
```clojure
phi/ISubscribe
(init-subscribers [_]
  ;; NOTE: These are wrapped in a vector.
  [(add-subscriber subscriber-a 5 [:event-type-a]
     ...)
   (add-subscriber subscriber-b 5 [:event-type-b]
     ...)])
```

Example using [`routing-table`](#routing-table):
```clojure
phi/ISubscribe
(init-subscribers [_]
  (phi/routing-table
    (a/sliding-buffer 10)
    [[:event-type-a] callback-a
     [:event-type-b] callback-b]))
```

##### Lifecycle Protocols
###### `IDisplayName`
###### `IShouldUpdate`
###### `IShouldUpdateForProps`
###### `IWillMount`
###### `IDidMount`
###### `IWillUnmount`
###### `IWillUpdate`
###### `IWillUpdateProps`
###### `IDidUpdate`
###### `IDidUpdateProps`
#### `mount-app`
#### `unmount-app`
#### `get-ref`
#### `get-dom-node`
#### `get-child-node`
#### `mounted?`


### Debugging
#### `start-debug-events!`
#### `stop-debug-events!`
#### `start-debug-conn!`
#### `stop-debug-conn!`

## Phi Examples

There are two [TodoMVC](http://todomvc.com/) examples:
* Using an associative db
  * [source](https://github.com/potetm/todomvc/tree/gh-pages/examples/phi-associative)
  * [working example](https://potetm.github.io/todomvc/examples/phi-associative/index.html)
* Using a datascript db
  * [source](https://github.com/potetm/todomvc/tree/gh-pages/examples/phi-datascript)
  * [working example](https://potetm.github.io/todomvc/examples/phi-datascript/index.html)

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
