# Development

```
core/   pure Kotlin/JVM, no dependencies: ELM327 driver + ISO-TP reassembly, RequestGuard, OBDb parser/catalogue,
        discovery, logger engine, HA discovery, MQTT 3.1.1 client, JSON
app/    Android: Bluetooth SPP, foreground service, OBDb download/cache, UI (framework widgets, no AndroidX)
```

- Tests: `./gradlew :core:selfTest` runs against a simulated ELM327/ECU (no JUnit). Set `MQTT_TEST_PORT` and run
  `core/src/test/fake_broker.py` to exercise the MQTT client too.
- Build: `./gradlew :app:assembleDebug` (needs the Android SDK). CI builds a release APK on every push and attaches it
  to a GitHub release for `v*` tags.
- Signing: CI reads `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` from repo
  secrets. Keep the keystore backed up, because losing it means users must reinstall.
- OBDb data is CC BY-SA 4.0 (see `NOTICE.md`).
