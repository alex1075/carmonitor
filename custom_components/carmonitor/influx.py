import logging

_LOGGER = logging.getLogger(__name__)

def _tag(s):
    return s.replace('\\', '\\\\').replace(',', '\\,').replace('=', '\\=').replace(' ', '\\ ')

def _str(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'

def lineProtocol(car, drive, rows):
    li = []
    for ts, sig, v in rows:
        if v is None:
            continue
        val = _str(v) if isinstance(v, str) else repr(float(v))
        li.append('carmonitor,car=' + _tag(car) + ',drive=' + _tag(drive) + ' ' + sig + '=' + val + ' ' + str(ts))
    return '\n'.join(li)

async def writeInflux(session, url, org, bucket, token, body):
    """InfluxDB v2 write API (also served by 1.8+, with token 'user:password' and bucket 'db/rp')."""
    if body == '':
        return True
    try:
        async with session.post(url.rstrip('/') + '/api/v2/write', params={'org': org, 'bucket': bucket, 'precision': 'ms'},
                                headers={'Authorization': 'Token ' + token}, data=body.encode('utf-8'), timeout=30) as r:
            if r.status >= 300:
                _LOGGER.warning('InfluxDB write failed: HTTP %s %s', r.status, (await r.text())[:200])
                return False
            return True
    except Exception as e:
        _LOGGER.warning('InfluxDB write failed: %s', e)
        return False
