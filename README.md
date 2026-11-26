# CarMonitor

Turns a spare Android phone and a Bluetooth ELM327 dongle into an always-on car telemetry logger for
**Home Assistant**, over MQTT. It works with any car [OBDb](https://github.com/OBDb) covers (about 700 make/model
repos), plus the generic SAE J1979 PIDs every OBD-II car speaks.

- **Pick your car** from the OBDb catalogue. The app downloads that model's signalset (and the make-level fallback), choosing
  the right year-range file, and caches it for offline use.
- **Discover** what *your* car actually answers, then log only that.
- **Home Assistant**: one MQTT-discovered device per car, sensors for the useful signals, trip and fault entities,
  and DPF regen entities on diesels.
- **Read-only by construction**: the app can't clear codes, write coding or run ECU routines (see *Safety*).
- **iPhone (preview)**: an iOS app for Bluetooth LE ELM327 adapters, installed with AltStore, sends drives to the
  CarMonitor HA integration. See the wiki's *iPhone* page.

Docs: see the [wiki](../../wiki) (sources in `docs/wiki/`). Licence: [MIT](LICENSE) for the code; OBDb data is CC BY-SA 4.0.

## Which Bluetooth OBD dongle to buy

The app needs a **classic Bluetooth (SPP) ELM327-compatible** adapter. BLE-only dongles are not supported yet. It also relies on
these AT commands working properly: `ATSH`, `ATCRA`, `ATH1`, `ATCAF1`, `ATAT1`, `ATSP`.

| Adapter | Link | Verdict |
|---|---|---|
| **OBDLink LX Bluetooth** (ScanTool) | Bluetooth 3.0, SPP | **Recommended.** STN11xx chip with the full ELM327 command set, fast, auto sleep (BatterySaver) so it can stay plugged in. Android/Windows only (no iOS). About £90–95 in the UK. |
| **Vgate iCar Pro (BT3.0/BLE4.0)** | dual mode | Budget option: use its classic BT side on Android. Check the listing says *BT3.0*. |
| Vgate vLinker MC (Bluetooth 3.0) | classic BT | Should work (Android/Windows classic). |
| Vgate iCar Pro **BLE 4.0** / **2S**, other BLE-only dongles | BLE only | Not supported yet. |
| Unbranded "ELM327 v2.1 / v1.5" clones (£3–10) | varies | Avoid. Many reject half the ELM327 command set. |

**Check yours:** if it pairs in Android's Bluetooth settings with a PIN (usually 1234 or 0000), it's classic. When it
connects, the app logs any AT commands the adapter rejected ("adapter rejected … likely a poor ELM327 clone").
Discovery also saves them in the profile.

**Leaving it plugged in:** pick an adapter with auto-sleep. A dongle that never sleeps will flatten a battery that's left for a week.

## Setup

1. Pair the dongle, either in Android Bluetooth settings or in the app with **Scan & pair…** (ignition on). Scanning asks for *Nearby devices* permission, declared `neverForLocation`, so no location access.
2. Install the APK (from [Releases](../../releases), or add the repo in Obtainium) and grant the Bluetooth and notification
   permissions.
3. **Car → Change vehicle** and search (for example `RAV4`, `Golf`, `Octavia`). The make is pre-filled from the VIN once discovery
   has run. Set the **model year** first: it selects the OBDb year file, and EU/JDM VINs carry no year code. If OBDb doesn't have your car,
   use **Generic OBD-II**.
4. **Home Assistant → Edit connection** offers three ways to reach Home Assistant. Then tap **Send test**; a *Connection test* sensor should appear on the car's HA device.
   - **MQTT broker:** HA's Mosquitto add-on, ideally over Tailscale/NetBird rather than an open port, with a username and password.
   - **Home Assistant API:** your HA URL plus a **long-lived access token** (HA → Profile → Security). The app publishes through HA's
     `mqtt.publish` service, so you only need HTTPS to HA: no broker port and no VPN. Updates are batched every 3 s.
   - **CarMonitor integration (preview):** install the integration from this repo (HACS), add it in HA, and paste the
     webhook URL it shows. No admin token on the phone, nothing lost when the signal drops (rows are sent late with their
     real times), and past drives can be uploaded. See the wiki's *HA integration* page.

   The password, token and webhook URL are masked on screen and stored encrypted with the Android Keystore.
5. Engine on → **Car → Discover**. Logging starts when discovery finishes. **Discover + sweep** also scans Mode 22 DID
   ranges you choose (Home Assistant → Advanced), which helps find manufacturer values that OBDb doesn't document yet.
   Each car's profile is saved, and sweep results are kept per VIN, so switching cars or reinstalling (**Import profile**)
   never means discovering again.
6. **Autostart:** under **Car → Car Bluetooth**, tick the car's own Bluetooth (its head unit). When it connects to the
   phone at ignition, CarMonitor starts and dials the OBD dongle. Tap **Allow background** once (Android's companion-device
   dialog) so this works with the app closed. Without it you get a tap-to-start notification instead.
7. Turn off battery optimisation for the app. Between drives the service also retries the dongle every 20 s.

## Home Assistant

The default topic base is `car/<make_model>` (for example `car/toyota_rav4`) and the device id is `carmonitor_<make_model>`. You can edit both
in the app. Sensors appear through MQTT discovery under that device:

- Signals OBDb marks with a `suggestedMetric`, plus core J1979 values (RPM, speed, coolant, oil, intake, battery voltage, load,
  fuel level, MIL, and DPF pressure/temperatures and regen status where the car has them). Every other supported signal gets a sensor too, so nothing is lost
  if you archive Home Assistant's history (for example to InfluxDB).
- Trip active / trip distance / last trip distance (km), fault count / codes / pending, and on diesels *DPF regen active* and
  *last trip regen*.
- `<base>/logger/status` is the availability topic, with a last-will message.

## The app

Three tabs, Material 3:
- **Drive:** status, speed, RPM, coolant and exhaust temperature, fuel, range and economy, DPF and the current trip, with a
  Start/Stop button. The app log is behind the icon at the top.
- **Car:** vehicle and tank size, OBD dongle, autostart, and the discovery profile (discover, sweep, import, export).
- **Home Assistant:** connection, naming, appearance, units, options, advanced settings, exports.

**GPS (optional, off by default):** *Record GPS track* adds position to each drive's CSV and saves a GPX track;
*Send location to HA* adds a car location tracker and a GPS trip distance sensor. Location is read only while the logger
runs, and the first switch-on shows a warning: don't use the app while driving.

**Android Auto:** a read-only dashboard (speed, RPM, temperatures, fuel and range, economy, trip, DPF regen) shows up in
Android Auto while the logger runs. Android Auto only lists template apps installed by Google Play, so mark the install
as Play once (Obtainium + Shizuku, or `pm install -i com.android.vending`); see the wiki's Android Auto page.

**Appearance:** *Extended* (large tiles) or *Compact* (one dense list) layout, and System, Light or Dark theme.
**Units:** km/h or mph, litres or UK gallons (economy then shows as mpg), °C or °F. Units only change what the phone shows:
Home Assistant always receives metric and converts with its own unit settings.

## Overlays: manufacturer PIDs OBDb doesn't have yet

An overlay is a JSON file in the OBDb v3 schema with two extra fields, `validated` and `note`. Its commands override OBDb's commands with the
same header and request. Unvalidated commands are published only as raw diagnostic sensors (`u16` state, hex in the attributes) and in
the per-drive CSV. That lets you compare them against FORScan or Car Scanner before trusting the scaling. Then set
`"validated": true`, import it (**Import overlay JSON**) and run Discover again. Bundled example: `overlay/Mazda-CX-5.json` (2.2 SkyActiv-D
DPF soot, regen-distance and EGT candidates), the car the project was first tested on. If you confirm a PID, consider contributing it to OBDb.

## Safety

`core/RequestGuard.kt` allowlists every byte sent to the adapter: services **01, 03, 07, 09, 22**, each with exact
lengths, plus a fixed set of AT commands. Everything else is refused, including `04`/`14` (clear DTCs), `10` (diagnostic session),
`11` (ECU reset), `27` (security access), `2E` (write by identifier), `2F` (IO control) and `31` (routine control, for example forced
regen). The self-test checks that discovery and logging send nothing else.

## Build

CI runs `./gradlew :core:selfTest :app:assembleRelease` on every push and attaches a signed APK to a GitHub release
for `v*` tags. One-time signing setup (so Obtainium updates install over the existing app):

```bash
keytool -genkeypair -v -keystore ~/keys/carmonitor.jks -alias carmonitor -keyalg RSA -keysize 4096 -validity 10000
base64 -i ~/keys/carmonitor.jks | gh secret set SIGNING_KEYSTORE_B64      # Linux: base64 -w0
gh secret set SIGNING_KEY_ALIAS -b carmonitor
gh secret set SIGNING_STORE_PASSWORD -b 'your-password'
gh secret set SIGNING_KEY_PASSWORD -b 'your-password'
```

Layout: `core/` is pure Kotlin/JVM with no dependencies (ELM327 driver with ISO-TP reassembly, request guard, OBDb parser and
catalogue, discovery, logger engine, HA discovery, MQTT 3.1.1 client, JSON), tested by `:core:selfTest` against a
simulated ECU. `app/` is the Android layer (Bluetooth SPP, foreground service, OBDb download, UI with framework widgets only).
`ios/CarMonitorKit` is the iOS app's logic in Swift (`swift run SelfTest`); `ios/CarMonitor` the SwiftUI app, generated with
XcodeGen (`ios/project.yml`). CI builds an unsigned `CarMonitor.ipa` on macOS and attaches it to tag releases. Both apps'
request guards are checked against `spec/guard-vectors.json`.

## Limits

- Classic Bluetooth only, for now. CAN cars are the main target. Older K-line/J1850 cars may need a different `ATSP`.
- OBDb coverage varies by model. Some repos are mostly petrol-car data, so diesel-specific values may need an overlay.
- When the uplink is down, live values are dropped. Retained ones (trip summary, DTCs, discovery) are flushed on reconnect.

## Licence and data

Code: [MIT](LICENSE). OBDb data: CC BY-SA 4.0, see `NOTICE.md`. Contributing: [CONTRIBUTING.md](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md) · [Security](SECURITY.md).
