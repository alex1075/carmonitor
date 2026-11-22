# CarMonitor (Android)

Bluetooth ELM327 → MQTT → Home Assistant logger for the Mazda CX-5 2.2 SkyActiv-D (KF).
It replaces the Pi Zero logger (`mazda_monitor.py` v3) with a spare Android phone. It publishes to the **same
`mazda/cx5/*` topics**, so the existing HA package (the 15 entities, regen and fault automations, and the dashboard) works without
any HA changes.

## What it does

- **Discover**: probes what the car actually answers and saves a vehicle profile on the phone.
  1. Mode 01 support bitmaps and the VIN (Mode 09 02)
  2. every command in the bundled [OBDb](https://github.com/OBDb) signalsets (SAE J1979 + Mazda CX-5) plus the
     local diesel overlay, filtered by model year
  3. *Discover + sweep* also scans Mode 22 DID ranges that you choose (Settings). Sweep hits go into the profile with
     their raw bytes.
- **Log**: polls only the signals in the profile, at the OBDb `freq` intervals. It also tracks trips (RPM/speed), regen (J1979 `01 8B`
  status if the ECU has it, otherwise a post-DPF EGT threshold and a soot drop), and reads stored and pending DTCs (Mode 03/07). Every drive is also
  written to a CSV (`ts_ms,key,signal,value,raw`) that you can export.
- **HA**: legacy topics get raw values (they're defined in YAML, so the app sends no discovery for them). Extra signals (ECT, EOT, J1979 DPF
  pressure/temps, regen status, OBDb CX-5 EGT, exhaust ΔP, EGR, oil dilution…) get MQTT discovery configs attached
  to the existing `mazda_cx5` device. `mazda/cx5/logger/status` is the availability topic, with a last-will message.

## Read-only by construction

`core/RequestGuard.kt` allowlists every byte sent to the adapter: services **01, 03, 07, 09, 22** with exact
lengths, plus a fixed set of AT commands. It refuses everything else, including `04`/`14` (clear DTCs), `10` (session),
`11` (reset), `27` (security), `2E` (write), `2F` (IO control) and `31` (routines, e.g. a forced regen). The self-test
checks that the guard holds and that discovery and logging send nothing else.

## Diesel PIDs: validate before trusting

OBDb has no soot, regen or distance-since-regen DIDs for this engine. `assets/overlay/mazda-skyactiv-d.json` holds
the v3 guesses (`22042B` soot, `220532` distance since regen, `2203CB` post-DPF EGT, plus neighbouring clusters), all
with `"validated": false`. Unvalidated DIDs are only published as raw diagnostic sensors
(`sensor.mazda_cx5_raw_7e0_22xxxx`, with `u16` as the state and the hex in attributes). They are never sent to the legacy topics.

To validate one: drive with FORScan or Car Scanner showing the named PID (PM_ACC, PM_ACC_DSD, PM_GEN…), compare it with the
exported CSV or the HA history, fix the `fmt` scaling, set `"validated": true`, then **Import overlay JSON** and **Discover** again.
Validated entries feed `soot_gL`, `pm_acc_dsd_gL`, `dist_since_regen` and `egt_post_dpf_c`.

## Setup

1. Pair the dongle in Android Bluetooth settings (classic SPP; the PIN is usually 1234 or 0000).
2. Open the app and grant the Bluetooth and notification permissions.
3. Pick the dongle. For the broker, use HA Mosquitto's Tailscale/NetBird IP, port 1883, and an HA user. Set the model year to **2017** (EU VINs have no year code, and
   some OBDb filters depend on the year).
4. Turn the engine on and tap **Discover**. Logging starts automatically when discovery finishes.
5. Turn off battery optimisation for the app. The service stays up between drives and reconnects when the dongle wakes.

## Build

CI (`.github/workflows/build.yml`) runs `:core:selfTest` and `:app:assembleRelease` on every push and attaches the
APK to a GitHub release when you push a `v*` tag. Point Obtainium at the repo's releases.

Updates only install in place if every build is signed with the same key. Create one once:

```bash
keytool -genkeypair -v -keystore carmonitor.jks -alias carmonitor -keyalg RSA -keysize 4096 -validity 10000
gh secret set SIGNING_KEYSTORE_B64 < <(base64 -w0 carmonitor.jks)
gh secret set SIGNING_STORE_PASSWORD; gh secret set SIGNING_KEY_ALIAS -b carmonitor; gh secret set SIGNING_KEY_PASSWORD
```

Local build: `./gradlew :core:selfTest :app:assembleDebug` (needs the Android SDK).

## Layout

- `core/`: pure Kotlin/JVM with no dependencies, unit-testable: ELM327 driver + ISO-TP reassembly, request guard,
  OBDb parser/decoder, discovery, logger engine, HA discovery, minimal MQTT 3.1.1 client, JSON.
- `app/`: Android: Bluetooth SPP link, foreground service, boot receiver, settings/live screen (framework
  widgets only, no AndroidX).

## Known limits / next steps

- Classic Bluetooth only (no BLE dongles yet).
- Autostart works after a phone reboot, and the service waits for the dongle. Waking only when the car's Bluetooth appears
  (CompanionDeviceManager presence) would save more battery.
- When the uplink is down, live values are dropped. Retained ones (trip summary, DTCs, discovery) are flushed on reconnect.
- OBDb data: CC BY-SA 4.0, see `NOTICE.md`.
