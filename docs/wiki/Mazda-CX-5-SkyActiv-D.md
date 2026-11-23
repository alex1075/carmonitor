# Mazda CX-5 2.2 SkyActiv-D (KF)

The project started as a DPF/EGR health monitor for this car, replacing a Pi Zero logger.

- **Preset:** choosing `Mazda-CX-5` keeps the Pi-logger layout: topics `mazda/cx5/{rpm,speed_kmh,fuel_pct,soot_gL,
  pm_acc_dsd_gL,egt_post_dpf_c,dist_since_regen,oil_dilution,trip_active,trip_distance_mi,trip/last_*,dtc/*}`, device
  `mazda_cx5`, and miles for distances. Your existing YAML package works unchanged.
- **Model year:** EU CX-5 VINs (`JMZ…`) carry no year code, so set it in Settings. Some OBDb CX-5 commands are year-filtered.
- **OBDb CX-5 coverage:** useful for EGT 1/2 (`2203F4/F5`), exhaust differential pressure (`2203DB`, raw volts), oil
  dilution events (`220435`) and EGR. It has **no soot load, regen status or distance-since-regen**, and much of the set is
  petrol data (VCT, purge).
- **Standard J1979 to look for:** `017A` (DPF ΔP), `017C` (DPF temps), `018B` (regen status, percent, average distance). These are
  free if the ECU supports them.
- **Bundled overlay** `overlay/Mazda-CX-5.json`: soot `22042B`, distance since regen `220532`, post-DPF EGT `2203CB`, plus
  neighbouring clusters. All are **unvalidated guesses**. Follow [[Overlays and validating PIDs|Overlays-and-Validating-PIDs]]
  with FORScan's PM_ACC / PM_ACC_DSD / PM_GEN as the reference.

**Fuel on the 2017 CX-5 diesel:** no fuel-rate PID answers (`015E` is absent, and OBDb marks Mazda `22F45E` as missing for
2017–2021). OBDb's `CX5_OXY` (`220451`) reads 55–64 % at idle, which is physically impossible (exhaust O2 ≤ 20.9 %), so it isn't
used. Standard MAF `0110` reads only ~1.4 g/s at idle, while MAF-A `0166` reads a plausible 4–7 g/s. Result: economy comes from
the fuel level (fill-to-fill) until an injection-quantity PID turns up among the sweep hits.

Regen detection uses `018B` if present, otherwise a post-DPF EGT threshold (450 °C) or a ≥ 1 g/L soot drop during a
trip.
