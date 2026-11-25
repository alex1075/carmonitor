import math
import re

SIG_RE = re.compile('^[A-Za-z0-9_]{1,64}$')
DRIVE_RE = re.compile('^[A-Za-z0-9_.-]{1,40}$')

def _text(v, n):
    if v is None:
        return None
    if isinstance(v, str) == False or len(v) > n:
        raise ValueError('bad text field')
    return v

def parsePayload(data, max_rows):
    """Validate one webhook body from the app. Raises ValueError with a reason.

    {"drive": "20261125-082845", "name": "My car", "status": "online",
     "defs": {"RPM": {"name": "Engine RPM", "unit": "rpm", "expire": 60, "text": false}},
     "rows": [[1795595335668, "RPM", 1500.0], ...]}
    """
    if isinstance(data, dict) == False:
        raise ValueError('body must be a JSON object')
    drive = data.get('drive')
    if isinstance(drive, str) == False or DRIVE_RE.match(drive) is None:
        raise ValueError('bad drive id')
    status = data.get('status')
    if status not in (None, 'online', 'offline'):
        raise ValueError('bad status')
    defs = {}
    raw_defs = data.get('defs') or {}
    if isinstance(raw_defs, dict) == False:
        raise ValueError('defs must be an object')
    for sig, d in raw_defs.items():
        if SIG_RE.match(sig) is None or isinstance(d, dict) == False:
            raise ValueError('bad def ' + str(sig)[:64])
        expire = d.get('expire', None)
        if expire is not None and (isinstance(expire, int) == False or expire < 10 or expire > 86400):
            raise ValueError('bad expire for ' + sig)
        defs[sig] = {'name': _text(d.get('name'), 80) or sig, 'unit': _text(d.get('unit'), 16),
                     'expire': expire, 'text': d.get('text', False) == True}
    rows = data.get('rows') or []
    if isinstance(rows, list) == False or len(rows) > max_rows:
        raise ValueError('rows must be a list of at most ' + str(max_rows))
    out = []
    for r in rows:
        if isinstance(r, list) == False or len(r) != 3:
            raise ValueError('row must be [ts_ms, signal, value]')
        ts, sig, v = r
        if isinstance(ts, int) == False or ts < 1000000000000 or ts > 9999999999999:
            raise ValueError('bad timestamp')
        if isinstance(sig, str) == False or SIG_RE.match(sig) is None:
            raise ValueError('bad signal id')
        if isinstance(v, bool) or (v is not None and isinstance(v, (int, float, str)) == False):
            raise ValueError('bad value for ' + sig)
        if isinstance(v, float) and math.isfinite(v) == False:
            raise ValueError('value not finite for ' + sig)
        if isinstance(v, str) and len(v) > 255:
            raise ValueError('value too long for ' + sig)
        out.append((ts, sig, v))
    return {'drive': drive, 'name': _text(data.get('name'), 64), 'status': status, 'defs': defs, 'rows': out}

def latestPerSignal(rows):
    li = {}
    for ts, sig, v in rows:
        if sig not in li or ts >= li[sig][0]:
            li[sig] = (ts, v)
    return li
