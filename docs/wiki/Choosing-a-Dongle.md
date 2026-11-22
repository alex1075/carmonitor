# Choosing a dongle

CarMonitor talks to the car through a **classic Bluetooth (SPP) ELM327-compatible** adapter. For reproducible results
it needs:

- **Classic Bluetooth / SPP.** BLE-only adapters aren't supported yet. If it pairs in Android's Bluetooth settings with a
  PIN (usually `1234` or `0000`), it's classic.
- **A working ELM327 command set**, specifically `ATSH` (set header), `ATCRA` (receive filter), `ATH1` (headers on),
  `ATCAF1`, `ATAT1` and `ATSP`. Mode 22 manufacturer PIDs depend on `ATSH`/`ATCRA`.
- **Auto-sleep**, if it stays plugged in.

| Adapter | Bluetooth | Notes |
|---|---|---|
| **OBDLink LX Bluetooth** (ScanTool) | 3.0, SPP | **Recommended reference adapter.** STN11xx chip, full ELM327 set plus extensions, fast, BatterySaver sleep (< 2 mA). Android/Windows only. About £90–95 in the UK. |
| Vgate iCar Pro (BT3.0/BLE4.0) | dual mode | Budget. Use the classic side on Android. Make sure the listing says BT3.0. |
| Vgate vLinker MC (BT 3.0) | classic | Android/Windows classic version. |
| Vgate iCar Pro BLE 4.0 / iCar Pro 2S | BLE only | **Not supported yet.** |
| Unbranded "ELM327 v1.5 / v2.1" clones | varies | Avoid. Many answer `?` to half the commands. |

## Check an adapter you already own

With the engine on, connect once. The app's log reports any AT command the adapter rejected:

```
adapter rejected ATCRA7E8, ATAT1 - likely a poor ELM327 clone, see README
```

The list is also saved in the profile as `adapterWarnings`. Standard Mode 01 data may still work on such adapters, but Mode
22 discovery usually won't.

## Reproducibility tip

When you share a profile or overlay (for example as an OBDb contribution), say which adapter and firmware you used. The
profile's `adapter` field records the `ATZ` banner.
