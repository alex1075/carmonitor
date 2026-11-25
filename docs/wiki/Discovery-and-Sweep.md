# Discovery and sweep

**Discover** probes the car in three passes and saves a *profile*. The logger then polls only what answered.

1. **Mode 01 bitmaps and VIN:** `0100`, `0120`, … list the standard PIDs the engine ECU supports, and `0902` gives the VIN.
2. **Signalset probe:** every command in SAE J1979 + the make fallback + your model's OBDb signalset + any overlay,
   filtered by model year, sent to its own ECU header (`7E0` engine, `7E1` gearbox, `760` …). Results:
   - positive reply → `SUPPORTED` (raw bytes saved)
   - `7F xx 31` requestOutOfRange, or another negative reply → `UNSUPPORTED`
   - `NO DATA` → `NO_RESPONSE` (wrong ECU, or that module is asleep)
   - `7F xx 78` responsePending → retried automatically
3. **Sweep (optional):** *Discover + sweep* scans Mode 22 DID ranges from Settings, for example `7E0:0300-04FF`, and records
   every DID that answers, even if no one has documented it yet. About 256 requests take 20–60 s on a good adapter.
   Ranges are capped at 4096 DIDs each, and a full 0000–FFFF sweep would take hours.

## Reading the profile
**Export profile** saves JSON with `vin`, `modelYear`, `adapter`, `adapterWarnings`, `vehicleRepo`, `signalsetFile`,
`mode01Supported`, `results[]` (key, support, raw, note) and `sweepHits[]`.

Sweep hits are your leads for new PIDs. Drive with the [[overlay workflow|Overlays-and-Validating-PIDs]] to find out
what each one means.

Re-run discovery after changing the vehicle, the model year or the overlay. Choosing a new vehicle deletes the old profile.

## Poll plan: choosing fast, medium and slow
A poll plan sets the tier of any command, sweep hits included, without rebuilding the app. It is a separate file per
VIN (`pollplan/<VIN>.json` in the app's storage), so discovery, re-discovery and **Import profile** never change it.
Import it in **Settings → Polling → Import plan**; **Export plan** saves the current car's plan.

```json
{ "format": "carmonitor-pollplan-1", "vin": "<your VIN>",
  "tiers": { "7E0:22DA9F": "fast", "7E0:220451": "medium", "7E0:22DAAA": "slow" } }
```

- Keys are command keys exactly as in the profile's `results[].key` and `sweepHits[].key` (header:request).
- Tiers are `fast`, `medium` or `slow`. Commands the plan doesn't list keep the app's own tier.
- A plan only applies when the car with that VIN is connected. Keys that car doesn't answer are ignored and named once in
  the app log at the start of the session.
- The log lists each override and its tier when the logger starts.

### Poll mode
- **Auto (current behaviour):** each tier has an interval (fast about 0.5 s, medium 15 s, slow 60 s), the fast tier
  stretches on a slow adapter, and every 4th request goes to the most overdue command.
- **Slots:** requests go out back to back in rounds of 6. **Fast slots per round** (1–3) and **Medium slots** (2–3)
  set the split, and the rest are slow (at least 1). Each tier's commands take turns. A Mode 01 multi-PID batch (up to 6
  PIDs) is one slot. Every Mode 22 DID is one slot. With 3/2/1, 4 fast items, 20 medium and 60 slow and a 1.2 s round,
  each fast item is read every 1.6 s, each medium every 12 s and each slow every 72 s. The log prints these effective
  intervals every minute.

Topics, HA entities and the drive CSV are the same in both modes. Polled sweep DIDs still record their raw bytes.
