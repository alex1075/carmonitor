from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.helpers.entity import EntityCategory

from .entity import CarEntity

async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities([LoggerOnline(entry)])

class LoggerOnline(CarEntity, BinarySensorEntity):
    _attr_device_class = BinarySensorDeviceClass.CONNECTIVITY
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _attr_name = 'Logger'

    def __init__(self, entry):
        super().__init__(entry, 'logger')

    @property
    def is_on(self):
        return self.online
