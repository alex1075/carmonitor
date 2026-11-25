from homeassistant.core import callback
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity import Entity

from . import is_online, signal_tick, signal_update
from .const import DOMAIN

class CarEntity(Entity):
    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, entry, key):
        self.entry = entry
        self._attr_unique_id = entry.entry_id + '_' + key
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, entry.entry_id)}, name=entry.title, manufacturer='CarMonitor')

    async def async_added_to_hass(self):
        self.async_on_remove(async_dispatcher_connect(self.hass, signal_update(self.entry.entry_id), self._on_update))
        self.async_on_remove(async_dispatcher_connect(self.hass, signal_tick(self.entry.entry_id), self._on_tick))

    @callback
    def _on_update(self, latest):
        self.async_write_ha_state()

    @callback
    def _on_tick(self):
        self.async_write_ha_state()

    @property
    def online(self):
        return is_online(self.entry)
