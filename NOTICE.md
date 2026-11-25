# Third-party code and data

## Leaflet
The drive map card bundles Leaflet 1.9.4 (https://leafletjs.com), unmodified, in
`custom_components/carmonitor/www/leaflet/`: Copyright (c) 2010-2023, Volodymyr Agafonkin; Copyright (c) 2010-2011,
CloudMade. BSD 2-Clause licence, full text in `custom_components/carmonitor/www/leaflet/LICENSE`. Map tiles are
© OpenStreetMap contributors (https://www.openstreetmap.org/copyright) and © CARTO (https://carto.com/attributions),
loaded by the browser at view time.

## OBDb

Vehicle signal definitions come from the OBDb project (https://github.com/OBDb), by its contributors,
licensed CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).

- Bundled, unmodified: `app/src/main/assets/obdb/SAEJ1979.json` (https://github.com/OBDb/SAEJ1979) and
  `app/src/main/assets/obdb/Mazda-CX-5.json` (https://github.com/OBDb/Mazda-CX-5), `signalsets/v3/default.json`,
  fetched 2026-11-22.
- Downloaded at runtime: the signalset of the vehicle you select, from `raw.githubusercontent.com/OBDb/<repo>`.

`app/src/main/assets/overlay/*.json` are local overlays in the same schema. If you share them or contribute them back to
OBDb, they fall under the same CC BY-SA 4.0 terms.
