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
