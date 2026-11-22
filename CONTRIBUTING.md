# Contributing

Thanks for helping. The most useful contributions are usually **data from real cars**, not code.

## Report what your car does
Open a [vehicle report](../../issues/new?template=vehicle_report.yml) with:
- make, model, year, engine/fuel, market (EU/UK/US…)
- the dongle (model and the `ATZ` banner, see [Choosing a dongle](docs/wiki/Choosing-a-Dongle.md))
- the exported **profile** JSON (remove the VIN if you prefer; the first 3 characters are enough)

If you confirmed a manufacturer PID, contribute it upstream to the car's [OBDb](https://github.com/OBDb) repo. That way
every app benefits, not just this one. Overlays in `app/src/main/assets/overlay/` are for values that are still being validated.

## Code
- `core/` stays **dependency-free pure Kotlin/JVM** and is tested by `./gradlew :core:selfTest`. New logic goes there,
  with a test against the `FakeCx5` simulator (or a new fake ECU).
- `app/` is a thin Android layer that uses framework widgets only (no AndroidX), so it builds fast and stays small.
- **Never widen `RequestGuard`** beyond read-only services. PRs that add writes, routines, session changes or
  DTC clearing will be declined; see [Safety](docs/wiki/Safety.md).
- Keep the CX-5 legacy topic layout working (existing Home Assistant installs depend on it).
- Run `./gradlew :core:selfTest` before opening a PR. CI also builds the APK.

## Style
Kotlin official style (`kotlin.code.style=official`), 4-space indent, and a short comment on *why* for anything protocol-related.
Commit messages: imperative subject line (< 72 chars) and a body explaining the reason for the change.

## Docs
Wiki pages live in `docs/wiki/` and are published to the GitHub wiki automatically on push to `main`. Edit them
there, not in the wiki UI, or your edits will be overwritten.

By contributing you agree your code is released under the [MIT licence](LICENSE), and data contributions to OBDb
fall under CC BY-SA 4.0.
