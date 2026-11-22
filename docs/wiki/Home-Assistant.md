# Home Assistant

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
- signals OBDb marks with a `suggestedMetric`, plus core J1979 ones the car answers (or every supported signal, with
  *Publish every supported signal*)
- trip, distance and fault entities. Trip summaries and DTCs are retained and stay visible while the car is off.
- live sensors use `expire_after`, so they show *unavailable* rather than a stale number when the car is off.

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

## Legacy CX-5 layout
Picking `Mazda-CX-5` applies the original Pi-logger layout (`mazda/cx5/*`, miles, device `mazda_cx5`, no discovery for
those entities because they live in YAML). See [[Mazda CX-5 2.2 SkyActiv-D|Mazda-CX-5-SkyActiv-D]].
