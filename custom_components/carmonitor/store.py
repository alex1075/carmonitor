import bisect
import sqlite3
import threading

LOCK = threading.Lock()
SCHEMA = [
    'CREATE TABLE IF NOT EXISTS rows (drive TEXT, ts INTEGER, sig TEXT, num REAL, txt TEXT, PRIMARY KEY (drive, ts, sig)) WITHOUT ROWID',
    'CREATE TABLE IF NOT EXISTS drives (drive TEXT PRIMARY KEY, first_ts INTEGER, last_ts INTEGER)',
    'CREATE TABLE IF NOT EXISTS signals (sig TEXT PRIMARY KEY, name TEXT, unit TEXT, expire INTEGER, text INTEGER)',
]

def openStore(path):
    con = sqlite3.connect(path, check_same_thread=False)
    con.execute('PRAGMA journal_mode=WAL')
    for s in SCHEMA:
        con.execute(s)
    con.commit()
    return con

def insertRows(con, drive, rows):
    """Store rows; duplicates (same drive, time and signal) are ignored, so re-sending is safe."""
    if len(rows) == 0:
        return 0
    li = []
    for ts, sig, v in rows:
        if isinstance(v, str):
            li.append((drive, ts, sig, None, v))
        else:
            li.append((drive, ts, sig, v, None))
    with LOCK:
        before = con.total_changes
        con.executemany('INSERT OR IGNORE INTO rows VALUES (?, ?, ?, ?, ?)', li)
        n = con.total_changes - before
        first = min(r[0] for r in rows)
        last = max(r[0] for r in rows)
        con.execute('INSERT INTO drives VALUES (?, ?, ?) ON CONFLICT(drive) DO UPDATE SET '
                    'first_ts = min(first_ts, excluded.first_ts), last_ts = max(last_ts, excluded.last_ts)', (drive, first, last))
        con.commit()
    return n

def driveSpan(con, drive):
    with LOCK:
        r = con.execute('SELECT first_ts, last_ts FROM drives WHERE drive = ?', (drive,)).fetchone()
    if r is None:
        return None
    return {'start': r[0], 'end': r[1]}

def saveSignals(con, defs):
    with LOCK:
        for sig, d in defs.items():
            con.execute('INSERT OR REPLACE INTO signals VALUES (?, ?, ?, ?, ?)',
                        (sig, d['name'], d['unit'], d['expire'], 1 if d['text'] == True else 0))
        con.commit()

def loadSignals(con):
    with LOCK:
        cur = con.execute('SELECT sig, name, unit, expire, text FROM signals')
        return {r[0]: {'name': r[1], 'unit': r[2], 'expire': r[3], 'text': r[4] == 1} for r in cur.fetchall()}

def listDrives(con, limit=500):
    with LOCK:
        cur = con.execute('SELECT d.drive, d.first_ts, d.last_ts, EXISTS (SELECT 1 FROM rows r WHERE r.drive = d.drive AND r.sig = ?) '
                          'FROM drives d ORDER BY d.first_ts DESC LIMIT ?', ('GPS_LAT', limit))
        return [{'drive': r[0], 'start': r[1], 'end': r[2], 'gps': r[3] == 1} for r in cur.fetchall()]

def driveSignals(con, drive):
    with LOCK:
        cur = con.execute('SELECT DISTINCT sig FROM rows WHERE drive = ? AND num IS NOT NULL ORDER BY sig', (drive,))
        return [r[0] for r in cur.fetchall() if r[0] not in ('GPS_LAT', 'GPS_LON')]

def _series(con, drive, sig):
    cur = con.execute('SELECT ts, num FROM rows WHERE drive = ? AND sig = ? AND num IS NOT NULL ORDER BY ts', (drive, sig))
    return cur.fetchall()

def driveTrack(con, drive, sig=None, max_gap_ms=10000):
    """[[ts, lat, lon, value]] for each GPS fix; value is the latest reading of sig at or before the fix (None if older than max_gap_ms)."""
    with LOCK:
        lat = _series(con, drive, 'GPS_LAT')
        lon = dict(_series(con, drive, 'GPS_LON'))
        vals = _series(con, drive, sig) if sig is not None else []
    times = [t for t, v in vals]
    out = []
    for ts, la in lat:
        if ts not in lon:
            continue
        v = None
        i = bisect.bisect_right(times, ts) - 1
        if i >= 0 and ts - times[i] <= max_gap_ms:
            v = vals[i][1]
        out.append([ts, la, lon[ts], v])
    return out
