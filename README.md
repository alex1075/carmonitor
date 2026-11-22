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

1. Pair the dongle in Android Bluetooth settings.
2. Install the APK (from [Releases](../../releases), or add the repo in Obtainium) and grant the Bluetooth and notification
   permissions.
3. **Vehicle → Choose vehicle…** and search (for example `RAV4`, `Golf`, `CX-5`). The make is pre-filled from the VIN once discovery
   has run. Set the **model year** first: it selects the OBDb year file, and EU/JDM VINs carry no year code. If OBDb doesn't have your car,
   use **Generic OBD-II only**.
4. Set the MQTT broker (HA's Mosquitto add-on, ideally reached over Tailscale/NetBird rather than an open port), a user and a password.
5. Engine on → **Discover**. Logging starts when discovery finishes. **Discover + sweep** also scans Mode 22 DID
   ranges you choose (Settings), which helps find manufacturer values that OBDb doesn't document yet.
6. Turn off battery optimisation for the app. The service waits between drives and reconnects when the dongle wakes.

## Home Assistant

The default topic base is `car/<make_model>` (for example `car/toyota_rav4`) and the device id is `carmonitor_<make_model>`. You can edit both
in the app. Sensors appear through MQTT discovery under that device:

- Signals OBDb marks with a `suggestedMetric`, plus core J1979 values (RPM, speed, coolant, oil, intake, battery voltage, load,
  fuel level, MIL, and DPF pressure/temperatures and regen status where the car has them). Tick *Publish every supported signal* for everything.
- Trip active / trip distance / last trip distance (km), fault count / codes / pending, and on diesels *DPF regen active* and
  *last trip regen*.
- `<base>/logger/status` is the availability topic, with a last-will message.

**Legacy CX-5 preset:** choosing `Mazda-CX-5` applies the old Pi-logger layout (`mazda/cx5/*`, miles, device
`mazda_cx5`), so an existing YAML package keeps working. Untick *Legacy CX-5 topics* to switch to the generic layout.

## Overlays: manufacturer PIDs OBDb doesn't have yet

An overlay is a JSON file in the OBDb v3 schema with two extra fields, `validated` and `note`. Its commands override OBDb's commands with the
same header and request. Unvalidated commands are published only as raw diagnostic sensors (`u16` state, hex in the attributes) and in
the per-drive CSV. That lets you compare them against FORScan or Car Scanner before trusting the scaling. Then set
`"validated": true`, import it (**Import overlay JSON**) and run Discover again. Bundled: `overlay/Mazda-CX-5.json` (2.2 SkyActiv-D
DPF soot, regen-distance and EGT candidates). If you confirm a PID, consider contributing it to OBDb.

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

## Limits

- Classic Bluetooth only, for now. CAN cars are the main target. Older K-line/J1850 cars may need a different `ATSP`.
- OBDb coverage varies by model. Some repos are mostly petrol-car data, so diesel-specific values may need an overlay.
- When the uplink is down, live values are dropped. Retained ones (trip summary, DTCs, discovery) are flushed on reconnect.

## Licence and data

Code: [MIT](LICENSE). OBDb data: CC BY-SA 4.0, see `NOTICE.md`. Contributing: [CONTRIBUTING.md](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md) · [Security](SECURITY.md).
