from homeassistant.components.device_tracker import TrackerEntity
from homeassistant.core import callback

from .entity import CarEntity

async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities([CarTracker(entry)])

class CarTracker(CarEntity, TrackerEntity):
    """Last GPS fix. Stays where the car was parked: a position doesn't expire."""
    _attr_name = 'Location'

    def __init__(self, entry):
        super().__init__(entry, 'location')
        self.lat = None
        self.lon = None
        self.acc = 0

    @callback
    def _on_update(self, latest):
        if 'GPS_LAT' in latest and 'GPS_LON' in latest:
            self.lat = latest['GPS_LAT'][1]
            self.lon = latest['GPS_LON'][1]
            self.acc = int(latest.get('GPS_ACC_M', (0, 0))[1] or 0)
            self.async_write_ha_state()

    @property
    def latitude(self):
        return self.lat

    @property
    def longitude(self):
        return self.lon

    @property
    def location_accuracy(self):
        return self.acc
