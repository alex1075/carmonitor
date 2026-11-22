# CarMonitor

An Android app that turns a spare phone and a Bluetooth ELM327 dongle into an always-on car telemetry logger for
Home Assistant over MQTT. It works with any car in [OBDb](https://github.com/OBDb), plus the generic SAE J1979 PIDs.

**Start here**
1. [[Choosing a dongle|Choosing-a-Dongle]]: which Bluetooth OBD adapter to buy (classic BT, a decent ELM327).
2. [[Getting started|Getting-Started]]: install, pick your car, connect the broker, run your first discovery.
3. [[Home Assistant|Home-Assistant]]: topics, entities, example automations.

**Going further**
- [[Discovery and sweep|Discovery-and-Sweep]]: what the probe does and how to read the profile
- [[Overlays and validating PIDs|Overlays-and-Validating-PIDs]]: adding manufacturer PIDs that OBDb doesn't have yet
- [[Mazda CX-5 2.2 SkyActiv-D|Mazda-CX-5-SkyActiv-D]]: the diesel preset this started from
- [[Safety|Safety]]: why the app can't write to your car
- [[Troubleshooting|Troubleshooting]]
- [[Development|Development]]: build, tests, release signing
