import logging
import os
import time
from datetime import timedelta

import voluptuous as vol
from aiohttp import web

from homeassistant.components import webhook, websocket_api
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.event import async_track_time_interval

from . import influx, payload, store
from .const import *

_LOGGER = logging.getLogger(__name__)
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)

def signal_update(entry_id):
    return DOMAIN + '_update_' + entry_id

def signal_tick(entry_id):
    return DOMAIN + '_tick_' + entry_id

def is_online(entry):
    rt = entry.runtime_data
    after = entry.options.get(CONF_OFFLINE_AFTER, DEFAULT_OFFLINE_AFTER)
    return rt['status'] == 'online' and time.time() - rt['last_seen'] < after

async def async_setup(hass, config):
    websocket_api.async_register_command(hass, ws_drives)
    websocket_api.async_register_command(hass, ws_track)
    return True

async def async_setup_entry(hass: HomeAssistant, entry):
    folder = hass.config.path(DOMAIN)
    await hass.async_add_executor_job(lambda: os.makedirs(folder, exist_ok=True))
    con = await hass.async_add_executor_job(store.openStore, folder + '/' + entry.entry_id + '.db')
    known = await hass.async_add_executor_job(store.loadSignals, con)
    entry.runtime_data = {'con': con, 'known': known, 'status': 'offline', 'last_seen': 0.0,
                          'add_sensor': None, 'position': None}
    webhook.async_register(hass, DOMAIN, entry.title, entry.data[CONF_WEBHOOK_ID], handleWebhook,
                           local_only=False, allowed_methods=['POST'])
    entry.async_on_unload(lambda: webhook.async_unregister(hass, entry.data[CONF_WEBHOOK_ID]))
    @callback
    def tick(now):
        async_dispatcher_send(hass, signal_tick(entry.entry_id))
    entry.async_on_unload(async_track_time_interval(hass, tick, timedelta(seconds=10)))
    entry.async_on_unload(entry.add_update_listener(reload_entry))
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True

async def async_unload_entry(hass, entry):
    ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if ok == True:
        await hass.async_add_executor_job(entry.runtime_data['con'].close)
    return ok

async def reload_entry(hass, entry):
    await hass.config_entries.async_reload(entry.entry_id)

def entry_for_webhook(hass, webhook_id):
    for e in hass.config_entries.async_entries(DOMAIN):
        if e.data.get(CONF_WEBHOOK_ID) == webhook_id and getattr(e, 'runtime_data', None) is not None:
            return e
    return None

async def handleWebhook(hass, webhook_id, request):
    entry = entry_for_webhook(hass, webhook_id)
    if entry is None:
        return web.json_response({'ok': False, 'error': 'not set up'}, status=404)
    try:
        data = await request.json()
    except ValueError:
        return web.json_response({'ok': False, 'error': 'body is not JSON'}, status=400)
    try:
        p = payload.parsePayload(data, MAX_ROWS)
    except ValueError as e:
        return web.json_response({'ok': False, 'error': str(e)}, status=400)
    rt = entry.runtime_data
    stored = await hass.async_add_executor_job(store.insertRows, rt['con'], p['drive'], p['rows'])
    if len(p['defs']) > 0:
        await hass.async_add_executor_job(store.saveSignals, rt['con'], p['defs'])
        rt['known'].update(p['defs'])
    rt['last_seen'] = time.time()
    if p['status'] is not None:
        rt['status'] = p['status']
    elif len(p['rows']) > 0:
        rt['status'] = 'online'
    async_dispatcher_send(hass, signal_update(entry.entry_id), payload.latestPerSignal(p['rows']))
    o = entry.options
    if o.get(CONF_INFLUX_URL, '') != '' and stored > 0:
        # shortcut: rows InfluxDB refuses stay only in the built-in store; add a re-export if that bites.
        body = influx.lineProtocol(entry.title, p['drive'], p['rows'])
        hass.async_create_background_task(influx.writeInflux(async_get_clientsession(hass), o[CONF_INFLUX_URL],
            o.get(CONF_INFLUX_ORG, ''), o.get(CONF_INFLUX_BUCKET, ''), o.get(CONF_INFLUX_TOKEN, ''), body), DOMAIN + '_influx')
    return web.json_response({'ok': True, 'rows': len(p['rows']), 'stored': stored})

def pick_entry(hass, entry_id):
    for e in hass.config_entries.async_entries(DOMAIN):
        if (entry_id is None or e.entry_id == entry_id) and getattr(e, 'runtime_data', None) is not None:
            return e
    return None

@websocket_api.websocket_command({vol.Required('type'): 'carmonitor/drives', vol.Optional('entry_id'): str})
@websocket_api.async_response
async def ws_drives(hass, connection, msg):
    e = pick_entry(hass, msg.get('entry_id'))
    if e is None:
        connection.send_error(msg['id'], 'not_found', 'no CarMonitor car set up')
        return
    drives = await hass.async_add_executor_job(store.listDrives, e.runtime_data['con'])
    connection.send_result(msg['id'], {'entry_id': e.entry_id, 'car': e.title, 'drives': drives})

@websocket_api.websocket_command({vol.Required('type'): 'carmonitor/track', vol.Required('drive'): str,
                                  vol.Optional('signal'): str, vol.Optional('entry_id'): str})
@websocket_api.async_response
async def ws_track(hass, connection, msg):
    e = pick_entry(hass, msg.get('entry_id'))
    if e is None:
        connection.send_error(msg['id'], 'not_found', 'no CarMonitor car set up')
        return
    con = e.runtime_data['con']
    sig = msg.get('signal')
    pts = await hass.async_add_executor_job(store.driveTrack, con, msg['drive'], sig)
    sigs = await hass.async_add_executor_job(store.driveSignals, con, msg['drive'])
    d = e.runtime_data['known'].get(sig, {}) if sig is not None else {}
    connection.send_result(msg['id'], {'points': pts, 'signals': sigs, 'unit': d.get('unit'),
                                       'names': {s: e.runtime_data['known'].get(s, {}).get('name', s) for s in sigs}})
