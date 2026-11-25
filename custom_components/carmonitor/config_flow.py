import voluptuous as vol

from homeassistant.components import webhook
from homeassistant.config_entries import ConfigFlow, OptionsFlow
from homeassistant.core import callback
from homeassistant.helpers.selector import TextSelector, TextSelectorConfig, TextSelectorType

from .const import *

class CarMonitorConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    async def async_step_user(self, user_input=None):
        if user_input is not None:
            wid = webhook.async_generate_id()
            return self.async_create_entry(title=user_input['name'], data={CONF_WEBHOOK_ID: wid},
                                           description_placeholders={'webhook_url': webhook.async_generate_url(self.hass, wid)})
        return self.async_show_form(step_id='user', data_schema=vol.Schema({vol.Required('name', default='My car'): str}))

    @staticmethod
    @callback
    def async_get_options_flow(config_entry):
        return CarMonitorOptionsFlow()

class CarMonitorOptionsFlow(OptionsFlow):
    async def async_step_init(self, user_input=None):
        if user_input is not None:
            return self.async_create_entry(data=user_input)
        o = self.config_entry.options
        schema = vol.Schema({
            vol.Required(CONF_OFFLINE_AFTER, default=o.get(CONF_OFFLINE_AFTER, DEFAULT_OFFLINE_AFTER)): vol.All(int, vol.Range(min=30, max=900)),
            vol.Optional(CONF_INFLUX_URL, default=o.get(CONF_INFLUX_URL, '')): str,
            vol.Optional(CONF_INFLUX_ORG, default=o.get(CONF_INFLUX_ORG, '')): str,
            vol.Optional(CONF_INFLUX_BUCKET, default=o.get(CONF_INFLUX_BUCKET, '')): str,
            vol.Optional(CONF_INFLUX_TOKEN, default=o.get(CONF_INFLUX_TOKEN, '')): TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD)),
        })
        url = webhook.async_generate_url(self.hass, self.config_entry.data[CONF_WEBHOOK_ID])
        return self.async_show_form(step_id='init', data_schema=schema, description_placeholders={'webhook_url': url})
