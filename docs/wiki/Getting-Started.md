# Getting started

## 1. Install
- Download `app-release.apk` from [Releases](https://github.com/alex1075/carmonitor/releases), **or**
- in [Obtainium](https://github.com/ImranR98/Obtainium), add `https://github.com/alex1075/carmonitor` to get updates automatically.

Grant **Bluetooth** and **notification** permissions on first launch. Then turn off battery optimisation for CarMonitor
(Settings → Apps → CarMonitor → Battery → Unrestricted), otherwise Android may stop the logger between drives.

## 2. Pair the dongle
With the ignition on, open **Car** and tap **Scan & pair**. It lists nearby *classic* Bluetooth devices (BLE-only gadgets are hidden), and you pick the dongle. Android then asks for the PIN (often `1234` or `0000`). Or pair it in Android's Bluetooth settings and pick it with **Choose paired**.

Scanning needs the *Nearby devices* permission. The app declares it `neverForLocation`, so it gets no location access.

## 3. Choose your car
1. Set the **model year** (Home Assistant → Advanced). It decides which OBDb file is used (`2012-2020.json`, …, or `default.json`). Many EU/JDM VINs
   carry no year code, so the app can't always read it.
2. **Car → Change vehicle** → search for the model (`RAV4`, `Golf`, `Octavia`) → pick it. The app downloads the signalset and the
   make-level fallback, and caches both.
3. Not listed? Pick the make on its own ("Toyota (all models)") or **Generic OBD-II**.
4. Set the **fuel tank size** from the handbook, so remaining fuel and range can be worked out.

## 4. Connect Home Assistant
Open **Home Assistant → Edit connection** and pick one:
- **MQTT broker:** the HA **Mosquitto** add-on, ideally reached over Tailscale/NetBird (use the HA box's tailnet IP), with a Home
  Assistant user and password. Don't expose 1883 to the internet.
- **Home Assistant API (token):** your HA URL (for example `https://home.example.org`) and a long-lived access token (HA → your
  profile → *Security* → *Long-lived access tokens*). The app calls HA's `mqtt.publish` service over HTTPS, so it works anywhere
  HA is reachable, with no VPN or broker port. HA's MQTT integration must be set up. Updates are batched every 3 s. Use https,
  because over plain http the token travels in clear.

Secrets are masked (with a *Show* toggle), are never displayed once saved (leave blank to keep), and are stored encrypted with the
Android Keystore.
- Tap **Send test**. On success a *Connection test* sensor appears under the car's device in HA (Settings → Devices → MQTT). On failure the message says why (wrong password, not authorised, unreachable).
- Topic base and device name default to the car (`car/toyota_rav4`, "Toyota RAV4"). Change them if you like.

## 5. Autostart with the car
A Bluetooth OBD dongle never connects to the phone by itself, so CarMonitor uses the **car's own Bluetooth** as the
"ignition on" signal:
1. Pair the phone with the car (its head unit) as usual.
2. **Car → Car Bluetooth** → tick it (several are allowed).
3. **Allow background** → approve Android's companion-device dialog. This lets the app start its logger from the
   background when the car connects. Without it you get a *tap to start* notification instead.

When the car's Bluetooth connects, the logger starts and dials the dongle immediately. When it disconnects
(ignition off), the trip ends after 10 s and the app parks until the next start, with no re-dialling overnight. If you haven't picked a car device,
the service (started on boot) keeps retrying the dongle every 20 s.

## 6. First drive
Engine on → **Car → Discover**. It takes 1–3 minutes on a good adapter, and logging starts straight after. Your car appears in HA under
*Settings → Devices → MQTT*. Export the profile (**Export profile**) to see what the car answered,
and to keep a copy: **Import profile** restores it after a reinstall without discovering again.

## 7. Make it yours
**Home Assistant → Appearance** switches between the *Extended* and *Compact* layouts and the System/Light/Dark theme.
**Units** switches km/h ↔ mph, litres ↔ UK gallons (economy in mpg) and °C ↔ °F. Units only affect the phone's display.
