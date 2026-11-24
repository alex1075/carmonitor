# Home Assistant

## Connection modes
- **MQTT broker:** direct to Mosquitto, with a last-will so `logger/status` turns `offline` if the phone vanishes.
- **HA API token:** HA's `mqtt.publish` service over HTTPS. Same topics and entities, coalesced every 3 s. There's no last-will:
  `offline` is published on a clean stop, and live sensors expire via `expire_after` otherwise.

## Topics (generic layout)
With base `car/<slug>`:

| Topic | Payload |
|---|---|
| `<base>/logger/status` | `online` / `offline` (retained, last-will) |
| `<base>/sig/<SIGNAL_ID>` | decoded value, for example `car/toyota_rav4/sig/ECT` → `88` |
| `<base>/trip_active` | `on` / `off` |
| `<base>/trip/distance_km`, `<base>/trip/last_distance_km` | km |
| `<base>/dtc/count`, `<base>/dtc/codes`, `<base>/dtc/pending` | `2`, `P0401,P24A5`, `none` |
| `<base>/regen_active`, `<base>/trip/last_regen` | diesels with DPF data only |
| `<base>/raw/<HDR>_<REQ>` | `{"u16":…,"hex":"…"}` for unvalidated overlay PIDs |

Signal IDs are OBDb's (for example `RPM`, `VSS`, `ECT`, `DPF1_DP`). Their names and units come from the signalset.

## Entities
Everything is announced through MQTT discovery under one device (`carmonitor_<slug>`):
- every supported signal the car answers. OBDb's curated ones (`suggestedMetric`) and core J1979 values are the main
  sensors; the rest are there too, so an archive of HA's history (for example InfluxDB) has everything
- trip, distance and fault entities. Trip summaries and DTCs are retained and stay visible while the car is off.
- live sensors use `expire_after`, so they show *unavailable* rather than a stale number when the car is off.

## Fuel, economy and range
| Topic | Meaning |
|---|---|
| `<base>/fuel/rate_lh` | fuel rate, L/h (measured or estimated) |
| `<base>/fuel/economy_l100km`, `…/economy_mpg` | instant economy while moving (UK mpg) |
| `<base>/fuel/trip_used_l`, `…/trip_l100km`, `…/trip_mpg` | this trip |
| `<base>/fuel/average_l100km` | long-run average (fill-to-fill when level-based) |
| `<base>/fuel/remaining_l`, `<base>/fuel/range_km` | tank contents and estimated range |
| `<base>/fuel/source` | `measured`, `estimated_maf_lambda`, `estimated_maf_o2`, `fuel_level` or `none` |

How it's worked out, best first:
1. **Measured** fuel rate (J1979 `015E` / `019D`, or a manufacturer PID).
2. **Estimate** from mass air flow and standard wide-band lambda / O2 % (`018C`):
   λ ≈ 20.9 / (20.9 − O2 %), fuel = MAF / (14.5 × λ) for diesel. Expect about ±10 %.
3. **Fuel level**: the level is median-smoothed (fuel sloshes) and gives litres per trip plus a *fill-to-fill* average
   that resets when it sees a refuel (> 4 L jump), even one made while parked. There's no instant economy in this mode.

Set the **tank size** in the app (Car tab, from the handbook), or "remaining" and "range" can't be computed unless the car
reports litres itself.

Everything is sent in metric. Choose km/h or mph, litres or gallons and °C or °F in the app for its own display; HA converts
with its own unit settings.

## Example automations

Fault code appears:
```yaml
triggers:
  - trigger: state
    entity_id: sensor.toyota_rav4_fault_codes
conditions:
  - condition: template
    value_template: "{{ trigger.to_state.state not in ['none', 'unknown', 'unavailable'] }}"
actions:
  - action: notify.notify
    data:
      message: "Car fault: {{ trigger.to_state.state }}"
```

Diesel: DPF regen ended mid-drive (don't switch off during a regen):
```yaml
triggers:
  - trigger: state
    entity_id: binary_sensor.toyota_rav4_dpf_regen_active
    to: "on"
actions:
  - action: notify.notify
    data:
      message: "DPF regenerating - keep driving if you can."
```

Entity ids depend on your device name. Check them under the device page.
