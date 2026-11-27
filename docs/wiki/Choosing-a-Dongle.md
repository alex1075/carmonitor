# Choosing a dongle

CarMonitor talks to the car through an **ELM327-compatible** adapter over **classic Bluetooth (SPP)** or **Bluetooth LE**. For
reproducible results it needs:

- **Classic Bluetooth / SPP, or BLE.** If it pairs in Android's Bluetooth settings with a PIN (usually `1234` or `0000`),
  it's classic. If it never asks to pair and only appears in **Scan BLE**, it's BLE: no pairing, the app connects directly.
  The BLE link looks for the usual serial layouts (service FFF0 with FFF1/FFF2, FFE0 with FFE1, or 18F0 with 2AF0/2AF1)
  and otherwise any service with one notify and one writable characteristic.
- **A working ELM327 command set**, specifically `ATSH` (set header), `ATCRA` (receive filter), `ATH1` (headers on),
  `ATCAF1`, `ATAT1` and `ATSP`. Mode 22 manufacturer PIDs depend on `ATSH`/`ATCRA`.
- **Auto-sleep**, if it stays plugged in.

| Adapter | Bluetooth | Notes |
|---|---|---|
| **OBDLink LX Bluetooth** (ScanTool) | 3.0, SPP | **Recommended reference adapter.** STN11xx chip, full ELM327 set plus extensions, fast, BatterySaver sleep (< 2 mA). Android/Windows only. About £90–95 in the UK. |
| Vgate iCar Pro (BT3.0/BLE4.0) | dual mode | Budget. Use the classic side on Android. Make sure the listing says BT3.0. |
| Vgate vLinker MC (BT 3.0) | classic | Android/Windows classic version. |
| Vgate iCar Pro BLE 4.0 / iCar Pro 2S | BLE only | Use **Scan BLE**. Untested on real hardware: please report how it goes. |
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
