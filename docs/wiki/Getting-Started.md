# Getting started

## 1. Install
- Download `app-release.apk` from [Releases](https://github.com/alex1075/carmonitor/releases), **or**
- in [Obtainium](https://github.com/ImranR98/Obtainium), add `https://github.com/alex1075/carmonitor` to get updates automatically.

Grant **Bluetooth** and **notification** permissions on first launch. Then turn off battery optimisation for CarMonitor
(Settings → Apps → CarMonitor → Battery → Unrestricted), otherwise Android may stop the logger between drives.

## 2. Pair the dongle
With the ignition on, tap **Scan & pair…**. It lists nearby *classic* Bluetooth devices (BLE-only gadgets are hidden), and you pick the dongle. Android then asks for the PIN (often `1234` or `0000`). Or pair it in Android's Bluetooth settings and pick it under **OBD dongle**.

Scanning needs the *Nearby devices* permission. The app declares it `neverForLocation`, so it gets no location access.

## 3. Choose your car
1. Set the **model year**. It decides which OBDb file is used (`2012-2020.json`, …, or `default.json`). Many EU/JDM VINs
   carry no year code, so the app can't always read it.
2. **Choose vehicle…** → search for the model (`RAV4`, `Golf`, `CX-5`) → pick it. The app downloads the signalset and the
   make-level fallback, and caches both.
3. Not listed? Pick the make on its own ("Toyota (all models)") or **Generic OBD-II only**.

## 4. Connect Home Assistant
- Broker: the HA **Mosquitto** add-on, ideally reached over Tailscale/NetBird (use the HA box's tailnet IP). Don't expose
  1883 to the internet.
- User and password: a Home Assistant user.
- Tap **Test HA connection**. On success a *Connection test* sensor appears under the car's device in HA (Settings → Devices → MQTT). On failure the message says why (wrong password, not authorised, unreachable).
- Topic base and device name default to the car (`car/toyota_rav4`, "Toyota RAV4"). Change them if you like.

## 5. First drive
Engine on → **Discover**. It takes 1–3 minutes on a good adapter, and logging starts straight after. Your car appears in HA under
*Settings → Devices → MQTT*. Export the profile (**Export profile**) to see what the car answered.
