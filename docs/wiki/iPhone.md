# iPhone (preview)

CarMonitor for iOS logs a Bluetooth LE ELM327 adapter and sends the drives to the
[[CarMonitor integration|Home-Assistant-Integration]], the same way the Android app's integration mode does. It is
installed locally with AltStore; it is not on the App Store.

## Which adapter
iPhones can't use classic-Bluetooth serial adapters. They need one that speaks **Bluetooth LE**: most cheap "OBD2 BT"
ELM327 clones that work with iPhone OBD apps do. A quick check: if an iPhone OBD app connects to it **without** the
adapter appearing in Settings → Bluetooth, it is Bluetooth LE. The app finds the usual clone layouts (services FFF0,
FFE0, 18F0) and otherwise any service with a notify and a write characteristic. Wi-Fi adapters are not supported.

## Install with AltStore
1. Install [AltStore](https://altstore.io) on the iPhone, with AltServer on your computer.
2. Download `CarMonitor.ipa` from the latest GitHub release, on the iPhone.
3. Open it with AltStore (Share → AltStore, or AltStore → My Apps → +).
4. With a free Apple ID, AltStore must refresh the app every 7 days (it does this over Wi-Fi when AltServer runs).

## Set up
1. **Setup → Scan for adapters** with the ignition on, and tap your adapter.
2. **Vehicle**: pick the model year, then search OBDb (for example "cx 5") and tap your car. Without one, the
   generic OBD-II signals are used.
3. **Home Assistant**: paste the integration's webhook URL, **Save and test**.
4. Allow Bluetooth, and location **Always** if you want the route while the screen is off.
5. **Car → Start logging**. With **Start when the car is on**, the app waits for the adapter and starts by itself
   when the car powers it, also in the background.

## What it does and doesn't do (v1)
- Same read-only safety rules as Android; both apps' guards are checked against one shared list of commands.
- The first connection asks the car which signals it supports (about a minute), then remembers them.
- Drive CSVs are in the Files app (On My iPhone → CarMonitor → drives). The upload is the same queue as Android's:
  dropouts only delay data, and **Upload drives** sends anything left over.
- Not in v1: MQTT and HA-token modes, the discovery sweep, overlays, trip and fuel values, CarPlay (a sideloaded app
  can't have it).
