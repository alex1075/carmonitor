import time

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorStateClass
from homeassistant.core import callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect

from . import signal_update
from .const import DEFAULT_EXPIRE, NO_SENSOR
from .entity import CarEntity

DEVICE_CLASS = {'°C': SensorDeviceClass.TEMPERATURE, 'km/h': SensorDeviceClass.SPEED, 'V': SensorDeviceClass.VOLTAGE,
                'kPa': SensorDeviceClass.PRESSURE, 'km': SensorDeviceClass.DISTANCE}

async def async_setup_entry(hass, entry, async_add_entities):
    made = set()
    @callback
    def add(sigs, latest):
        li = []
        for sig in sigs:
            if sig in made or sig in NO_SENSOR:
                continue
            made.add(sig)
            li.append(CarSensor(entry, sig, latest.get(sig)))
        if len(li) > 0:
            async_add_entities(li)
    add(list(entry.runtime_data['known']), {})
    @callback
    def on_update(latest):
        add([s for s in latest if s not in made], latest)
    entry.async_on_unload(async_dispatcher_connect(hass, signal_update(entry.entry_id), on_update))

class CarSensor(CarEntity, SensorEntity):
    def __init__(self, entry, sig, first):
        super().__init__(entry, sig)
        self.sig = sig
        self.ts, self.value = first if first is not None else (0, None)
        d = entry.runtime_data['known'].get(sig, {})
        self._attr_name = d.get('name') or sig
        self.expire = d.get('expire') or DEFAULT_EXPIRE
        if d.get('text') != True:
            self._attr_native_unit_of_measurement = d.get('unit')
            self._attr_state_class = SensorStateClass.MEASUREMENT
            self._attr_device_class = DEVICE_CLASS.get(d.get('unit'))

    @callback
    def _on_update(self, latest):
        if self.sig not in latest:
            return
        ts, v = latest[self.sig]
        # Backlog rows older than what is shown only go to the store.
        if ts >= self.ts:
            self.ts = ts
            self.value = v
            self.async_write_ha_state()

    @property
    def available(self):
        return self.online == True and self.value is not None and time.time() * 1000 - self.ts < self.expire * 1000

    @property
    def native_value(self):
        return self.value
