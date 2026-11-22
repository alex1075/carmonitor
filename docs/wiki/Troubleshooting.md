# Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| "dongle not reachable" | Ignition off (many dongles sleep), not paired, or another app holds the connection. Close Car Scanner/Torque. |
| Scan finds nothing | Ignition on? Some dongles only advertise for a minute after power-up. BLE-only dongles are hidden (not supported yet). |
| "adapter rejected AT…" | Poor clone. See [[Choosing a dongle|Choosing-a-Dongle]]. |
| Discovery finds almost nothing | Wrong model year or vehicle; a K-line/J1850 car (try another `ATSP`); or Mode 22 blocked by a gateway (some newer cars). |
| "vehicle signalset not downloaded" | Choose the vehicle again while online. |
| OBDb search: "rate limit" | GitHub allows 60 unauthenticated API requests an hour. Wait, or use the cached list (*Search*, not *Refresh list*). |
| No entities in HA | Tap **Test HA connection**: it names the problem (auth, VPN, port). In HA, *Settings → Devices → MQTT*. |
| Anything else | **Export app log** and attach it to a [bug report](https://github.com/alex1075/carmonitor/issues/new?template=bug_report.yml). |
| Entities "unavailable" when parked | Expected: live sensors expire when nothing is publishing. Trip and DTC entities stay. |
| Logger stops overnight | Battery optimisation. Set the app to *Unrestricted*. |
