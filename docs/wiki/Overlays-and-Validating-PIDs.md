# Overlays and validating PIDs

OBDb is community-built, and some cars (or engine variants) lack manufacturer PIDs you care about. Diesel DPF values are the
classic example. An **overlay** adds them without forking OBDb.

## Format
Same as an OBDb v3 signalset, plus two optional command fields:

```json
{ "commands": [
  { "hdr": "7E0", "rax": "7E8", "cmd": {"22": "042B"}, "freq": 5,
    "validated": false,
    "note": "Soot load guess; confirm against FORScan",
    "signals": [
      { "id": "MZD_SOOT_GL", "name": "DPF soot load", "path": "Diesel",
        "fmt": { "len": 16, "div": 100, "unit": "gramsPerLiter" } } ] } ] }
```

`fmt`: `bix` is the bit offset (MSB-first, from the first byte after the reply echo) and `len` is in bits. Value = raw × `mul` / `div` +
`add`. `sign` means two's complement. `map` is an enum lookup.

## Workflow
1. Add candidates with `"validated": false`. **Import overlay JSON** → **Discover**.
2. Drive with FORScan / Car Scanner showing the named parameter at the same time. CarMonitor publishes the candidate
   as a raw diagnostic sensor (`u16` state, hex in the attributes) and writes it to the drive CSV (**Export last drive**).
3. Line the raw value up against the reference and work out the scaling (two points are usually enough for `mul`/`div`/`add`).
4. Set `"validated": true`, re-import and re-discover. The signal becomes a normal HA sensor.
5. Consider opening a PR on the OBDb repo for your car, and say which [[dongle|Choosing-a-Dongle]] you used.

Unvalidated values never reach normal topics, so a wrong guess can't trigger your automations.
