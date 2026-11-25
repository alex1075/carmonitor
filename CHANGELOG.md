# Changelog

## [Unreleased]
### Added
- **Upload drives** button (CarMonitor integration mode): sends every drive the integration hasn't fully accepted,
  now, with a count of what's left.
### Fixed
- **"Not connected" while the car was off**, in every connection mode: the status only updated during a drive. The
  app now checks the link every 30 s (MQTT connects, the HA API is asked, the integration gets an empty post).

## [0.8.0] - 2026-11-25
### Added
- **Home Assistant integration (preview)**, `custom_components/carmonitor`, installable from HACS. Webhook in (no
  admin token on the phone), values stored with their real timestamps so late rows land at the right time, offline
  status built in, a built-in store for the coming drive map, and an optional copy to your own InfluxDB. See the wiki.
- **App: *CarMonitor integration* connection mode.** Paste the integration's webhook URL. The drive CSVs are the
  queue: rows go out every 3 s and, after a dropout, the backlog follows with the real timestamps. *Upload past drives*
  sends the older drive files too.

## [0.7.2] - 2026-11-24
### Changed
- **Much faster live values.** Speed, RPM, coolant and fuel rate now update every 0.5 s on the phone and in Android Auto
  (was 3 s). Home Assistant gets at most one value a second per sensor, to keep its database and InfluxDB sensible.
  - Several standard PIDs go out in one request (J1979 allows up to six), split back per PID.
  - The adapter is told how many replies to expect, so it answers as soon as the ECU does instead of waiting out its
    timeout. Only used for single-frame replies, and dropped per request (or for the adapter) if it misbehaves.
  - Requests to the ECU the adapter is already addressed to go first, saving header-switch round trips.
  - The fast group stretches itself if it would take more than half the adapter's time, so everything else still gets
    polled. The log shows the measured poll rate once a minute.
- An ECU that ignores extra PIDs in a request makes the app fall back to one PID per request for that session.
- Android Auto refreshes every second; the phone dashboard twice a second.

### Fixed
- **Car stayed "online" in HA when the phone lost signal (HA API token mode).** Fast sensors now expire after 60 s
  without data (was 180 s), and the status topic repeats `online` every 30 s. A new blueprint
  (`docs/ha/carmonitor-offline.yaml`, see the wiki) marks the car offline 90 s after the heartbeat stops.

## [0.7.1] - 2026-11-24
### Fixed
- **Exhaust and DPF sensors flicking to unavailable in Home Assistant.** On a busy adapter the slow polling tier could be
  starved for minutes, longer than HA's 3-minute expiry. Now every fourth request goes to the most overdue command
  whatever its tier, exhaust-temperature and particulate-filter signals poll in the 15 s tier, and slow diagnostic
  sensors get a 15-minute expiry.
- Android Auto docs: *Unknown sources* doesn't apply to template apps; the install has to be marked as from Google Play
  (Obtainium + Shizuku, or `pm install -i com.android.vending`). See the wiki.

## [0.7.0] - 2026-11-24
### Added
- **Android Auto dashboard**: a read-only, glanceable list (speed and RPM, coolant and exhaust, fuel and range, economy,
  trip, DPF regen) refreshed every 3 s, in the units you chose. Built on the Car App Library templates, so it works with
  a rotary controller and no touchscreen. Because CarMonitor isn't on the Play Store, turn on Android Auto's developer
  settings and enable *Unknown sources* once.

## [0.6.1] - 2026-11-24
### Added
- **GPS (optional, off by default)**, read only while logging:
  - *Record GPS track*: position, speed and accuracy rows in the drive CSV, plus a `.gpx` track per drive (export it from
    Home Assistant → Data & logs).
  - *Send location to HA*: a `device_tracker` for the car (last position is retained, so HA shows where it's parked) and
    a *GPS trip distance* sensor.
  - A one-time warning before switching either on: don't use the app while driving; use at your own discretion.
- Location permission is while-in-use only. If the logger starts from the background (car Bluetooth) Android may refuse
  location for that drive; the log says so.

## [0.6.0] - 2026-11-24
### Changed
- **New Material 3 interface** (Jetpack Compose): Drive, Car and Home Assistant tabs, cards, switches and a Start/Stop button.
- Settings save as you change them (no Save button).
### Added
- **Layout**: Extended (large tiles) or Compact (dense list). **Theme**: System, Light or Dark.
- **Units** for the phone's display: km/h or mph, litres or UK gallons (economy in mpg), °C or °F. HA still receives metric.
- Drive screen: live speed, RPM, coolant, exhaust, fuel with range and economy, DPF and trip, plus the app log.
### Docs
- README and wiki are car-agnostic; the CX-5 page is now a worked example.

## [0.5.6] - 2026-11-24
### Removed
- "Legacy CX-5 Pi-logger topics": every car, the CX-5 included, uses Home Assistant discovery (trip, faults, regen).
- "Force diesel": the fuel type comes from the car (J1979 0151).
- "Publish every supported signal": always on, so the HA long-term archive gets everything.
### Added
- **Sweep results are permanent per car (VIN)**: stored separately from the profile, only ever added to, and merged
  into every profile for that car. Vehicle switches, re-discovery and format upgrades can no longer lose them.
- **Import profile**: restores an exported profile (no discovery needed) and always keeps its sweep hits, even from
  old-format exports.
### Fixed
- After the car was switched off, the app could keep re-dialling the dongle every 30 s instead of parking, when
  the "no ECU replies" watchdog released the dongle before the car's Bluetooth dropped (seen 08:03-08:15 today).
### Changed
- No vehicle is pre-selected on a fresh install (no CX-5 default).
- Re-picking a car the app already knows also restores its MQTT topics and HA device, so its sensors keep their IDs.

## [0.5.5] - 2026-11-24
### Added
- **Saved profile per car**: each vehicle keeps its own discovery profile, sweep hits and fuel settings (tank,
  fuel state). Switching back to a car the app already knows restores them, with no re-discovery.
- **Tiered polling**: speed, RPM, coolant and fuel inputs every 3 s; Home Assistant-visible signals every 15 s;
  diagnostics and sweep DIDs every 60 s. On a slow ELM327 the dashboard values no longer wait ~35 s for a full cycle.
### Changed
- "Generic OBD-II only" asks for confirmation.
- Changing the vehicle restarts a running session straight away with the new signals and topics, instead of the
  old car's commands carrying on until the next drive.

## [0.5.4] - 2026-11-24
### Fixed
- Discovery looped forever and never logged: an OBDb entry on a non-11-bit header (ATSHD016) was refused by the
  read-only guard, which aborted the whole discovery, so no profile was saved and the next connect started again.
  Such entries are now skipped (noted in the profile), and a refused command can never abort discovery.
- The dongle's own Bluetooth connect no longer counts as a car trigger while discovery is running.

## [0.5.2] - 2026-11-23
### Changed
- No vehicle-specific fuel defaults: tank size is a setting for every car (OBDb carries signals, not specs), and
  diesel/petrol is detected from the car (J1979 0151) with the tick-box only as an override.
  **Upgrading:** re-enter your tank size (e.g. CX-5: 56 L 2WD, 58 L AWD).

## [0.5.1] - 2026-11-23
### Added
- **Car Bluetooth disconnect ends the session**: when the head unit / CMU disconnects (10 s grace), the trip is closed and
  the dongle released. With a car Bluetooth device set, the app then *parks* (no re-dialling) until the car connects again,
  which saves the phone's and the car's battery.
### Fixed
- Auto-connect could miss rides: a session that had stopped hearing from the ECU never released the dongle, so the next
  car-Bluetooth trigger was ignored. Now: no ECU reply for 60 s releases the dongle and returns to waiting, a trigger
  during a silent session forces a reconnect, and a session with nothing to poll refuses to start.
### Added
- Persistent app log (survives restarts, rotates at 512 KB), included in **Export app log**; the trigger receiver logs
  there even when the logger isn't running.

## [0.5.0] - 2026-11-23
### Added
- Discovery also probes OBDb's "debug" commands (159 on the CX-5, e.g. EGT 1/2 at 2203F4/F5); existing profiles
  re-discover once automatically, keeping their sweep hits.
- Unidentified sweep hits are polled and logged raw (CSV + diagnostic sensors) to help identify them; toggle in settings.
- Fuel rate from injection quantity (mm³/stroke × cylinders × rpm/2) once a quantity PID is validated.
- CX-5 overlay candidates from the first sweep: injection quantity (22044B), EGT 3 (2203F6),
  per-cylinder injector corrections (220436–0439).
### Fixed
- Values outside a signal's declared min/max are rejected, e.g. OBDb's FUEL_TIMING (015D) had offset −38665 instead
  of −210 (also corrected in a global fixes overlay).
- Discovery skips commands whose service isn't on the read-only allowlist (OBDb debug entries using KWP service 21)
  instead of stopping.

## [0.4.0] - 2026-11-23
### Added
- **Fuel, economy and range**: fuel rate (L/h), instant and trip economy (L/100 km and UK mpg), trip fuel used,
  fuel remaining (L), range (km) and a long-run average, each a Home Assistant sensor.
  Sources, best first: measured fuel rate, then MAF + standard lambda/O2 estimate, then fuel level with a
  fill-to-fill average that resets on refuelling. The source is published as *Fuel rate source*.
- Tank size and diesel/petrol settings (CX-5 preset: 56 L; AWD is 58 L).
### Fixed
- Implausible manufacturer values (e.g. exhaust O2 above 20.95 %) are rejected for fuel maths.

## [0.3.1] - 2026-11-23
### Fixed
- Logger could sit at "polling 0 commands" forever after a discovery that ran while the ECU was asleep. Empty profiles are no longer saved, and a saved profile with no supported signals triggers re-discovery.
- The dongle's own connection no longer logs a spurious "car Bluetooth connected" event while logging.

## [0.3.0] - 2026-11-23
### Added
- **Home Assistant API mode**: log in with a long-lived access token instead of a broker username/password. Publishes via
  HA's `mqtt.publish` over HTTPS (no broker port or VPN), batched every 3 s, retained messages retried.
- **Connection settings** submenu with mode choice, masked secrets with a *Show* toggle, and an in-dialog *Test*.
- **Autostart on the car's Bluetooth**: tick the head unit (for example the Mazda CMU); when it connects, the logger starts and
  dials the OBD dongle. Companion-device link for background start, with a tap-to-start notification fallback.
### Security
- MQTT password and HA token encrypted at rest with the Android Keystore (AES-256-GCM); old plaintext passwords migrated.
### Fixed
- The password field was readable: `setSingleLine()` after the password input type removed the masking.

## [0.2.2] - 2026-11-22
### Fixed
- Vehicle picker list now closes on selection; a progress dialog shows while the signalset downloads.
- Screen content no longer hidden under the status/navigation bars (Android 15+ edge-to-edge).
### Added
- **Test HA connection**: checks the broker login and publishes a *Connection test* sensor to the car's HA device; broker refusals explained in plain words.
- **Export app log**: app log + this app's logcat + last crash report in one text file.

## [0.2.1] - 2026-11-22
### Fixed
- Crash loop on connect: `cancelDiscovery()` needs `BLUETOOTH_SCAN` on Android 12+; removed, and the worker can no longer crash the service.
### Added
- **Scan & pair…**: find and pair a classic Bluetooth OBD dongle from inside the app.

## [0.2.0] - 2026-11-22
### Added
- Car-agnostic mode: OBDb catalogue search, runtime signalset download with year-range file choice and make fallback.
- Per-vehicle MQTT topics and HA device; generic trip/DTC entities via discovery.
- Default HA sensors from OBDb `suggestedMetric` and core J1979 signals.
- Adapter health check (rejected AT commands) recorded in the profile.
- VIN WMI make suggestion.
- Licence (MIT), contributing guide, code of conduct, security policy, issue/PR templates, wiki sync.

## [0.1.0] - 2026-11-22
### Added
- First release: ELM327 classic-BT logger for the Mazda CX-5 2.2 SkyActiv-D with discovery/sweep,
  read-only request guard, legacy `mazda/cx5` topics, CSV drive logs, overlay import, CI release signing.
