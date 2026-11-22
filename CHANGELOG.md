# Changelog

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
