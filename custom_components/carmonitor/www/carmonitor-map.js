// CarMonitor drive map: the line of one stored drive, optionally coloured by one signal.
const BASE = '/carmonitor_static/';
let leafletP = null;

function loadLeaflet() {
  if (window.L && window.L.map) return Promise.resolve(window.L);
  if (leafletP === null) {
    leafletP = new Promise((ok, bad) => {
      const s = document.createElement('script');
      s.src = BASE + 'leaflet/leaflet.js';
      s.onload = () => ok(window.L);
      s.onerror = () => { leafletP = null; bad(new Error('could not load the map library')); };
      document.head.appendChild(s);
    });
  }
  return leafletP;
}

const GAP_MS = 60000;   // no GPS fix for a minute: leave a hole rather than a straight line across it
const BUCKETS = 24;
const GREY = '#9e9e9e';

function colourAt(t) {
  return 'hsl(' + Math.round(240 * (1 - t)) + ', 85%, 45%)';
}

function metres(a, b) {
  const r = Math.PI / 180, dLa = (b[1] - a[1]) * r, dLo = (b[2] - a[2]) * r;
  const h = Math.sin(dLa / 2) ** 2 + Math.cos(a[1] * r) * Math.cos(b[1] * r) * Math.sin(dLo / 2) ** 2;
  return 12742000 * Math.asin(Math.sqrt(h));
}

function isSpeed(s, names) {
  return /^(VSS|SPEED|VEHICLE_SPEED)$/i.test(s) || /\bspeed\b/i.test(names[s] || '');
}

function fmtTime(ts) {
  return new Date(ts).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit', second: '2-digit'});
}

function fmtDrive(d) {
  const a = new Date(d.start), b = new Date(d.end);
  return a.toLocaleString([], {day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'}) +
    ' – ' + b.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'});
}

function fmtNum(v) {
  return Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2);
}

class CarMonitorMap extends HTMLElement {
  setConfig(config) {
    this._config = Object.assign({height: 360}, config || {});
    if (this._built === true) this._load();
  }

  static getStubConfig() { return {}; }
  getCardSize() { return 7; }
  getGridOptions() { return {columns: 12, rows: 7, min_rows: 5}; }

  set hass(hass) {
    const first = this._hass === undefined;
    this._hass = hass;
    if (first === true) this._build();
  }

  _build() {
    this._built = true;
    const root = this.attachShadow({mode: 'open'});
    root.innerHTML = '<link rel="stylesheet" href="' + BASE + 'leaflet/leaflet.css">' +
      '<style>' +
      'ha-card{overflow:hidden}' +
      '.head{padding:12px 16px 4px;font-size:16px;font-weight:500}' +
      '.row{display:flex;gap:8px;align-items:center;padding:4px 16px}' +
      '.row label{width:52px;color:var(--secondary-text-color);font-size:13px}' +
      'select{flex:1;min-width:0;padding:6px;border-radius:6px;border:1px solid var(--divider-color);' +
      'background:var(--card-background-color);color:var(--primary-text-color);font:inherit}' +
      '#map{margin:8px 0 0;background:var(--secondary-background-color)}' +
      '.foot{display:flex;gap:12px;align-items:center;padding:8px 16px 12px;font-size:13px;color:var(--secondary-text-color)}' +
      '.bar{flex:0 0 120px;height:10px;border-radius:5px;background:linear-gradient(90deg,' +
      [0, 0.25, 0.5, 0.75, 1].map(colourAt).join(',') + ')}' +
      '.legend{display:flex;gap:6px;align-items:center}.stats{margin-left:auto}' +
      '.msg{padding:16px;color:var(--secondary-text-color)}' +
      '</style>' +
      '<ha-card><div class="head"></div>' +
      '<div class="row"><label>Drive</label><select id="drive"></select></div>' +
      '<div class="row"><label>Colour</label><select id="sig"></select></div>' +
      '<div id="map"></div><div class="msg" hidden></div>' +
      '<div class="foot"><span class="legend" hidden><span class="lo"></span><span class="bar"></span><span class="hi"></span></span>' +
      '<span class="stats"></span></div></ha-card>';
    this._el = id => root.querySelector(id);
    this._el('#drive').addEventListener('change', () => this._showDrive(this._el('#drive').value, null));
    this._el('#sig').addEventListener('change', () => this._showDrive(this._drive, this._el('#sig').value));
    this._load();
  }

  _say(text) {
    const m = this._el('.msg');
    m.textContent = text;
    m.hidden = text === '';
  }

  async _load() {
    const map = this._el('#map');
    map.style.height = Number(this._config.height) + 'px';
    try {
      const L = await loadLeaflet();
      if (this._map === undefined) {
        this._map = L.map(map, {preferCanvas: true, zoomControl: true});
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',
          {maxZoom: 19, attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'}).addTo(this._map);
        this._map.setView([this._hass.config.latitude, this._hass.config.longitude], 12);
        new ResizeObserver(() => this._map.invalidateSize()).observe(map);
        this._layer = L.layerGroup().addTo(this._map);
      }
      const msg = {type: 'carmonitor/drives'};
      if (this._config.car) msg.entry_id = String(this._config.car);
      const r = await this._hass.callWS(msg);
      this._entry = r.entry_id;
      this._el('.head').textContent = this._config.title || ('CarMonitor: ' + r.car);
      const sel = this._el('#drive');
      sel.replaceChildren();
      for (const d of r.drives) {
        const o = document.createElement('option');
        o.value = d.drive;
        o.textContent = fmtDrive(d) + (d.gps === true ? '' : ' (no GPS)');
        o.disabled = d.gps !== true;
        sel.appendChild(o);
      }
      const firstGps = r.drives.find(d => d.gps === true);
      if (firstGps === undefined) { this._say('No drive with GPS yet. Turn on "Send location to HA" in the app.'); return; }
      this._say('');
      sel.value = firstGps.drive;
      await this._showDrive(firstGps.drive, null);
    } catch (e) {
      this._say('CarMonitor map: ' + (e.message || e));
    }
  }

  async _showDrive(drive, sig) {
    this._drive = drive;
    const ask = s => {
      const m = {type: 'carmonitor/track', drive: drive, entry_id: this._entry};
      if (s) m.signal = s;
      return this._hass.callWS(m);
    };
    try {
      let r = await ask(sig);
      if (sig === null) {
        // new drive: pick the configured signal, else speed, else plain line
        const want = this._config.signal;
        const pick = r.signals.includes(want) ? want : r.signals.find(s => isSpeed(s, r.names));
        if (pick !== undefined) { sig = pick; r = await ask(pick); } else { sig = ''; }
        const s = this._el('#sig');
        s.replaceChildren();
        for (const id of [''].concat(r.signals)) {
          const o = document.createElement('option');
          o.value = id;
          o.textContent = id === '' ? 'None' : (r.names[id] || id);
          s.appendChild(o);
        }
        s.value = sig;
      }
      this._draw(r, sig);
    } catch (e) {
      this._say('CarMonitor map: ' + (e.message || e));
    }
  }

  _draw(r, sig) {
    const L = window.L, pts = r.points, unit = r.unit || '';
    this._layer.clearLayers();
    if (pts.length === 0) { this._say('This drive has no GPS fixes.'); return; }
    this._say('');
    const vals = pts.map(p => p[3]).filter(v => typeof v === 'number');
    const coloured = sig !== '' && vals.length > 0;
    let lo = 0, hi = 0;
    if (coloured === true) { lo = Math.min.apply(null, vals); hi = Math.max.apply(null, vals); }
    const plain = getComputedStyle(this).getPropertyValue('--primary-color').trim() || '#03a9f4';
    const colourOf = v => {
      if (coloured === false) return plain;
      if (typeof v !== 'number') return GREY;
      const t = hi > lo ? Math.min(BUCKETS - 1, Math.floor((v - lo) / (hi - lo) * BUCKETS)) / (BUCKETS - 1) : 0.5;
      return colourAt(t);
    };
    let dist = 0, group = [pts[0]], col = colourOf(pts[0][3]);
    const flush = () => {
      if (group.length < 2) return;
      const g = group;
      L.polyline(g.map(p => [p[1], p[2]]), {color: col, weight: 5, opacity: 0.9})
        .on('click', e => this._popup(e.latlng, g, sig, r.names, unit)).addTo(this._layer);
    };
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i];
      if (b[0] - a[0] > GAP_MS) { flush(); group = [b]; col = colourOf(b[3]); continue; }
      dist += metres(a, b);
      const c = colourOf(a[3]);
      if (c !== col) { flush(); group = [a]; col = c; }
      group.push(b);
    }
    flush();
    const ends = [[pts[0], '#2e7d32', 'Start'], [pts[pts.length - 1], '#c62828', 'End']];
    for (const [p, c, t] of ends) {
      L.circleMarker([p[1], p[2]], {radius: 7, color: '#fff', weight: 2, fillColor: c, fillOpacity: 1})
        .bindTooltip(t + ' ' + fmtTime(p[0])).addTo(this._layer);
    }
    this._map.fitBounds(L.latLngBounds(pts.map(p => [p[1], p[2]])), {padding: [24, 24]});
    const legend = this._el('.legend');
    legend.hidden = coloured === false;
    this._el('.lo').textContent = coloured === true ? fmtNum(lo) : '';
    this._el('.hi').textContent = coloured === true ? fmtNum(hi) + ' ' + unit : '';
    const mins = Math.round((pts[pts.length - 1][0] - pts[0][0]) / 60000);
    this._el('.stats').textContent = (dist / 1000).toFixed(1) + ' km · ' + mins + ' min';
  }

  _popup(at, group, sig, names, unit) {
    let best = group[0], bd = Infinity;
    for (const p of group) {
      const d = (p[1] - at.lat) ** 2 + (p[2] - at.lng) ** 2;
      if (d < bd) { bd = d; best = p; }
    }
    const div = document.createElement('div');
    div.textContent = fmtTime(best[0]) + (sig !== '' ? ' · ' + (names[sig] || sig) + ': ' +
      (typeof best[3] === 'number' ? fmtNum(best[3]) + ' ' + unit : 'no reading') : '');
    window.L.popup().setLatLng([best[1], best[2]]).setContent(div).openOn(this._map);
  }
}

if (customElements.get('carmonitor-map') === undefined) customElements.define('carmonitor-map', CarMonitorMap);
window.customCards = window.customCards || [];
if (window.customCards.some(c => c.type === 'carmonitor-map') === false) {
  window.customCards.push({type: 'carmonitor-map', name: 'CarMonitor drive map',
    description: 'The line of a stored drive, optionally coloured by one signal.'});
}
