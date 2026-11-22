# Changelog

## [Unreleased]
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
