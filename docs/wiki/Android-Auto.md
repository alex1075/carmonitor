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
CarMonitor comes from GitHub, not the Play Store, so Android Auto hides it until you allow unknown sources:
1. Android Auto settings on the phone → scroll down and tap **Version** ten times → accept.
2. ⋮ menu → **Developer settings** → tick **Unknown sources**.
3. Reconnect to the car. CarMonitor appears in the app launcher.

## Notes
- It's built with Google's Car App Library templates, so it works with a rotary controller on cars that offer no
  touchscreen in Android Auto.
- With the logger stopped, the title shows its state and the rows show "—".
