# Android Auto

CarMonitor adds a **read-only dashboard** to Android Auto: one list, refreshed every 3 s, in the units you chose in the app.

| Row | Shows |
|---|---|
| Speed · RPM | from the car |
| Coolant · exhaust | exhaust only where the car reports it |
| Fuel · range | litres/gallons left (needs the tank size) or the level in % |
| Economy | this trip, else the long-run average |
| Trip | distance so far |
| DPF | regenerating or not (diesels) |

It only shows what the phone's logger is already reading. Nothing in Android Auto talks to the car, and there are no
controls, so there's nothing to fiddle with while driving.

## Enable it (once)
Android Auto only lists apps built on its templates (like this one) when Google Play installed them. Its
*Unknown sources* developer setting covers media and messaging apps, not template apps, so on its own it isn't enough.
Mark CarMonitor as installed by Play, keeping your data (no uninstall):

- **Obtainium + Shizuku (keeps working across updates):** start Shizuku (wireless debugging works without a computer),
  then in Obtainium set CarMonitor's *Installation method* to Shizuku and turn on *Set Google Play as the installation
  source*. Reinstall the current version from Obtainium.
- **Shell (Shizuku shell such as aShell, or `adb shell`):** `pm install -r -i com.android.vending /sdcard/Download/<apk>`.
  `-r` keeps your profiles and settings. A later install by another installer undoes it.

Check with `dumpsys package <package> | grep installer` (should show `com.android.vending`), force stop Android Auto and
reconnect. CarMonitor then appears in the launcher and under *Customise launcher*.

## Notes
- It's built with Google's Car App Library templates, so it works with a rotary controller on cars that offer no
  touchscreen in Android Auto.
- With the logger stopped, the title shows its state and the rows show "—".
