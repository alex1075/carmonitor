# Safety

CarMonitor is **read-only by construction**. `core/RequestGuard.kt` checks every line sent to the adapter against an
allowlist:

- OBD/UDS services **01** (live data), **03** (stored DTCs), **07** (pending DTCs), **09** (vehicle info), **22**
  (ReadDataByIdentifier), each with an exact payload length
- a fixed set of AT commands (no `ATMA`, no programmable-parameter writes)

Refused, among others:

| Service | What it does | Why it's blocked |
|---|---|---|
| `04` / `14` | clear DTCs | erases fault history and readiness monitors |
| `10` | diagnostic session control | gateway to programming sessions |
| `11` | ECU reset | |
| `27` | security access | unlocks protected services |
| `2E` | write data by identifier | changes coding/adaptations |
| `2F` | input/output control | moves actuators |
| `31` | routine control | e.g. a forced DPF regen, injector tests |
| `34`–`37`, `3D` | download/upload, write memory | flashing |

The self-test (`./gradlew :core:selfTest`) checks that the guard refuses these, and that discovery and logging send only
allowed requests to a simulated ECU.

Mode 22 reads are harmless to the ECU, but they do add bus traffic. Sweeps are rate-limited, so run them parked with the
engine on.
