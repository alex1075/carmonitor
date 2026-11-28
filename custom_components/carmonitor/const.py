DOMAIN = 'carmonitor'
PLATFORMS = ['sensor', 'binary_sensor', 'device_tracker']
CONF_WEBHOOK_ID = 'webhook_id'
CONF_OFFLINE_AFTER = 'offline_after'
CONF_INFLUX_URL = 'influx_url'
CONF_INFLUX_ORG = 'influx_org'
CONF_INFLUX_BUCKET = 'influx_bucket'
CONF_INFLUX_TOKEN = 'influx_token'
DEFAULT_OFFLINE_AFTER = 90
DEFAULT_EXPIRE = 60
MAX_ROWS = 20000
# Position goes to the device tracker, not to sensors.
NO_SENSOR = ['GPS_LAT', 'GPS_LON', 'GPS_ACC_M']

CARD_PATH = '/carmonitor_static'
CARD_VERSION = '0.2.1'
MAX_SIGNALS = 2000
