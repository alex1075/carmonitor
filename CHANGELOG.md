# Changelog

## [0.5.6] - 2026-11-24
### Removed
- "Legacy CX-5 Pi-logger topics": every car, the CX-5 included, uses Home Assistant discovery (trip, faults, regen).
- "Force diesel": the fuel type comes from the car (J1979 0151).
- "Publish every supported signal": always on, so the HA long-term archive gets everything.
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
