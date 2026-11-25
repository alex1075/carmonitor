# Home Assistant integration (preview)

An optional custom integration, an alternative to MQTT and the HA API token. The phone posts batches to a webhook, so:

- **No admin token on the phone.** The webhook URL is the only credential; keep it private.
- **Nothing is lost when the signal drops.** Each value carries its real timestamp. Rows sent late are stored at the
  time they were read; sensors show only what is still current. Re-sending is harmless (duplicates are ignored).
- **Offline built in.** The *Logger* connectivity sensor turns off, and every sensor goes unavailable, after 90 s
  without data (configurable). No blueprint needed.
- **Built-in store.** Every value is kept in a SQLite file under `config/carmonitor/` (included in HA backups). It feeds
  the drive map. Optionally every value is also copied to your own InfluxDB, with its real timestamp.

> Status: preview.

## Install
1. HACS → ⋮ → *Custom repositories* → `https://github.com/alex1075/carmonitor`, type *Integration*. Install
   *CarMonitor*, then restart HA.
2. Settings → Devices & services → *Add integration* → *CarMonitor*. Name the car. HA shows the webhook URL.
3. Options (on the integration's card): offline timeout, and InfluxDB if you want a copy there.
4. In the app: **Home Assistant → Edit connection → CarMonitor integration**, paste the webhook URL, **Test**, **Save**,
   then stop and start the logger.

## Drive map card
The integration ships a dashboard card; nothing else to install. After updating the integration, restart HA and reload
the browser, then add it to a dashboard (*Add card* → *CarMonitor drive map*, or YAML):

```yaml
type: custom:carmonitor-map
car: My car        # optional: the car's name or entry id; default is the car that drove most recently
signal: VSS        # optional: the signal to colour by when a drive opens; default is speed, if the car reports it
height: 360        # optional, pixels
```

- **Drive**: every stored drive, newest first. Drives without GPS are greyed out (turn on *Send location to HA*).
- **Colour**: *None* for a plain line, or one signal at a time, coloured blue (lowest on the drive) to red (highest).
  Grey means no reading of that signal within 10 s of the GPS fix. A GPS gap over a minute leaves a hole.
- Tap the line for the time and value at that point. The footer shows the distance and duration.
- Your drive data stays in HA. The browser loads map tiles (OpenStreetMap data) from CARTO, as HA's own map does; the
  dark style follows HA's dark mode.

## What the app sends
The drive CSV is the queue. Every 3 s the app sends each drive file's rows past what that file already had accepted
(at most 5000 per request), then the latest status, names and derived values (trip, fuel, DTCs). After a dropout it
carries on from where it stopped, oldest first, so the gap fills in with the real times. How far each file got is kept
in `.sent` next to the drives.

- **Upload past drives**: also sends the drive files from before you switched to the integration. Leave it off to
  start fresh.
- No duplicates: drives are keyed by their date and time (`drive-20261125-082845.csv` → `20261125-082845`).
  Before sending a drive with no record in `.sent`, the app asks; if HA already has its newest reading it is skipped.
  Each reply carries `have` (`{"start", "end"}` of what is stored for that drive, or `null`). Rows sent twice are
  ignored anyway.
- Raw sweep candidates stay in the CSV only.
- With *Send location to HA* on, GPS rows go into the CSV for the integration even if *Record GPS track* is off.

## InfluxDB (optional)
InfluxDB 2.x: URL (for example `http://influxdb:8086`), organisation, bucket and an API token with write access.
InfluxDB 1.8+: leave organisation empty, bucket `database/retention` (or just `database`), token `user:password`.
Points are written as measurement `carmonitor`, tags `car` and `drive`, one field per signal.

## Webhook format
`POST /api/webhook/<id>` with JSON:

```json
{"drive": "20261125-082845", "status": "online",
 "defs": {"RPM": {"name": "Engine RPM", "unit": "rpm", "expire": 60}},
 "rows": [[1795595335668, "RPM", 1500.0], [1795595335668, "GPS_LAT", 55.9]]}
```

- `rows`: `[time in ms, signal id, value]`, a number or short text; at most 20 000 per request.
- `defs`: names and units, sent when a signal first appears. `expire`: seconds before that sensor goes unavailable.
- `status`: `offline` on a clean stop. Any rows imply `online`.
- `GPS_LAT`/`GPS_LON` feed the *Location* tracker and the map, not sensors.
- Reply: `{"ok": true, "rows": 2, "stored": 2}`; 400 with a reason for a malformed body.
