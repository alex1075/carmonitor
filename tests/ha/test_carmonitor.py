import time

from homeassistant import config_entries
from homeassistant.setup import async_setup_component

from custom_components.carmonitor import store, influx, payload
from custom_components.carmonitor.const import DOMAIN

T0 = 1795595335000

def test_store_dedupe_and_track(tmp_path):
    con = store.openStore(str(tmp_path) + '/x.db')
    rows = [(T0, 'GPS_LAT', 55.9), (T0, 'GPS_LON', -3.1), (T0 - 500, 'VSS', 30.0), (T0 + 2000, 'GPS_LAT', 55.91),
            (T0 + 2000, 'GPS_LON', -3.11), (T0 + 1900, 'VSS', 32.0), (T0, 'DTC', 'P0401')]
    assert store.insertRows(con, 'd1', rows) == 7
    assert store.insertRows(con, 'd1', rows) == 0
    assert store.driveTrack(con, 'd1', 'VSS') == [[T0, 55.9, -3.1, 30.0], [T0 + 2000, 55.91, -3.11, 32.0]]
    assert store.driveSignals(con, 'd1') == ['VSS']
    d = store.listDrives(con)
    assert d[0]['drive'] == 'd1' and d[0]['gps'] == True and d[0]['start'] == T0 - 500

def test_payload_rejects_junk():
    for bad in [[], {'drive': '../x'}, {'drive': 'd', 'rows': [[1, 'RPM', 1]]}, {'drive': 'd', 'rows': [[T0, 'a b', 1]]},
                {'drive': 'd', 'rows': [[T0, 'RPM', True]]}, {'drive': 'd', 'status': 'x'}]:
        try:
            payload.parsePayload(bad, 100)
            assert False, bad
        except ValueError:
            pass

def test_influx_line():
    assert influx.lineProtocol('My car', 'd1', [(T0, 'RPM', 1500), (T0, 'DTC', 'P0"1')]) == \
        'carmonitor,car=My\\ car,drive=d1 RPM=1500.0 ' + str(T0) + '\ncarmonitor,car=My\\ car,drive=d1 DTC="P0\\"1" ' + str(T0)

async def test_webhook_end_to_end(hass, hass_client_no_auth, hass_ws_client):
    assert await async_setup_component(hass, 'http', {})
    r = await hass.config_entries.flow.async_init(DOMAIN, context={'source': config_entries.SOURCE_USER})
    r = await hass.config_entries.flow.async_configure(r['flow_id'], {'name': 'Test car'})
    assert r['type'] == 'create_entry'
    entry = r['result']
    await hass.async_block_till_done()
    client = await hass_client_no_auth()
    url = '/api/webhook/' + entry.data['webhook_id']
    now = int(time.time() * 1000)
    body = {'drive': '20261125-082845', 'defs': {'RPM': {'name': 'Engine RPM', 'unit': 'rpm', 'expire': 60}},
            'rows': [[now - 1000, 'RPM', 1490.0], [now, 'RPM', 1500.0], [now, 'GPS_LAT', 55.9], [now, 'GPS_LON', -3.1],
                     [now - 3600000, 'VSS', 40.0]]}
    resp = await client.post(url, json=body)
    assert resp.status == 200
    assert (await resp.json())['stored'] == 5
    await hass.async_block_till_done()
    assert hass.states.get('sensor.test_car_engine_rpm').state == '1500.0'
    assert hass.states.get('binary_sensor.test_car_logger').state == 'on'
    assert hass.states.get('device_tracker.test_car_location').attributes['latitude'] == 55.9
    # an hour-old backlog value is stored but not shown as current
    assert hass.states.get('sensor.test_car_vss').state == 'unavailable'
    resp = await client.post(url, json=body)
    assert (await resp.json())['stored'] == 0
    assert (await client.post(url, json={'drive': 'x', 'rows': [[1, 'RPM', 1]]})).status == 400
    ws = await hass_ws_client(hass)
    await ws.send_json({'id': 1, 'type': 'carmonitor/drives'})
    m = await ws.receive_json()
    assert m['result']['drives'][0]['drive'] == '20261125-082845'
    await ws.send_json({'id': 2, 'type': 'carmonitor/track', 'drive': '20261125-082845', 'signal': 'RPM'})
    m = await ws.receive_json()
    assert m['result']['points'] == [[now, 55.9, -3.1, 1500.0]]
    # phone goes quiet: offline after the timeout
    entry.runtime_data['last_seen'] = time.time() - 200
    from homeassistant.helpers.dispatcher import async_dispatcher_send
    async_dispatcher_send(hass, 'carmonitor_tick_' + entry.entry_id)
    await hass.async_block_till_done()
    assert hass.states.get('binary_sensor.test_car_logger').state == 'off'
    assert hass.states.get('sensor.test_car_engine_rpm').state == 'unavailable'
