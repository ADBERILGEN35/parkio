import { buildRasterStyle, type LatLng } from '@parkio/geo';

/**
 * Self-contained HTML document hosting MapLibre GL JS inside a WebView — the
 * isolated map renderer. RN talks to it only through the bridge:
 * `window.__parkio_dispatch(json)` inbound, `ReactNativeWebView.postMessage`
 * outbound.
 *
 * Design notes (pen `mob/map`): the OSM raster basemap is desaturated to the
 * calm pale look via a CSS filter (dark mode inverts to deep navy); spots are
 * DOM markers — a white pill with a live Freshness Ring + mm:ss countdown and
 * a caret, pulsing halo when selected. Result counts are level-capped (≤50),
 * so DOM markers stay cheap and pixel-match the design.
 */

/** MapLibre GL JS version — matches the web app's dependency line. */
const MAPLIBRE_VERSION = '4.7.1';

export interface MapHtmlColors {
  /** Freshness ramp. */
  fresh: string;
  aging: string;
  expiring: string;
  /** Ring track + pill chrome. */
  track: string;
  pillBg: string;
  pillText: string;
  muted: string;
  /** Selected pulse + user dot. */
  primary: string;
  userDot: string;
  userHalo: string;
}

export interface MapHtmlOptions {
  center: LatLng;
  zoom: number;
  mode: 'light' | 'dark';
  colors: MapHtmlColors;
  /** Show interactive spot markers (main map). Pickers pass false. */
  interactiveSpots?: boolean;
}

export interface MapSpotMarker {
  id: string;
  lat: number;
  lng: number;
  createdAt: string;
  expiresAt: string;
  /** Live statuses tick the ring; others render a static muted pill. */
  live: boolean;
  /** Suspicious flag renders the warning glyph. */
  warning?: boolean;
}

export function buildMapHtml(options: MapHtmlOptions): string {
  const { center, zoom, mode, colors } = options;
  const style = buildRasterStyle();
  const styleJson = JSON.stringify(style);
  const colorsJson = JSON.stringify(colors);
  const interactive = options.interactiveSpots !== false;

  const canvasFilter =
    mode === 'dark'
      ? 'invert(1) hue-rotate(200deg) saturate(0.22) brightness(0.82) contrast(0.9)'
      : 'saturate(0.32) contrast(0.94) brightness(1.05)';

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
  <link href="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.css" rel="stylesheet" />
  <script src="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.js"></script>
  <style>
    html, body, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: ${
      mode === 'dark' ? '#0B1626' : '#ECEFF3'
    }; overflow: hidden; }
    .maplibregl-canvas { filter: ${canvasFilter}; }
    .maplibregl-ctrl-attrib { font-size: 9px; opacity: 0.75; }
    .maplibregl-ctrl-bottom-left { display: none; }

    .pk-marker { position: relative; display: flex; flex-direction: column; align-items: center; cursor: pointer; }
    .pk-pill {
      display: flex; align-items: center; gap: 4px;
      background: var(--pill-bg); color: var(--pill-text);
      border-radius: 999px; padding: 4px 8px 4px 4px;
      box-shadow: 0 4px 14px rgba(0,0,0,0.14);
      font: 600 11px -apple-system, 'Inter', system-ui, sans-serif;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .pk-caret {
      width: 0; height: 0; margin-top: -1px;
      border-left: 5px solid transparent; border-right: 5px solid transparent;
      border-top: 6px solid var(--pill-bg);
      filter: drop-shadow(0 2px 2px rgba(0,0,0,0.08));
    }
    .pk-ring { display: block; }
    .pk-warn { font-size: 10px; margin-left: 1px; }
    .pk-pulse, .pk-pulse::before, .pk-pulse::after { position: absolute; border-radius: 999px; pointer-events: none; }
    .pk-pulse {
      top: 50%; left: 50%; width: 64px; height: 64px; margin: -38px 0 0 -32px;
      border: 1.5px solid var(--pulse); opacity: 0.5;
      animation: pk-pulse 2.2s ease-out infinite;
    }
    .pk-pulse::before { content: ''; inset: -14px; border: 1.5px solid var(--pulse); opacity: 0.32; }
    .pk-pulse::after { content: ''; inset: -30px; border: 1px solid var(--pulse); opacity: 0.18; }
    @keyframes pk-pulse {
      0% { transform: scale(0.85); opacity: 0.55; }
      70% { transform: scale(1.12); opacity: 0.18; }
      100% { transform: scale(1.2); opacity: 0; }
    }
    @media (prefers-reduced-motion: reduce) { .pk-pulse { animation: none; } }
    .pk-selected .pk-pill { outline: 2px solid var(--pulse); }

    .pk-user { width: 16px; height: 16px; border-radius: 50%; background: var(--user-dot);
      border: 3px solid #fff; box-shadow: 0 0 0 6px var(--user-halo), 0 2px 6px rgba(0,0,0,0.25); }
  </style>
</head>
<body>
  <div id="map"></div>
  <script>
    (function () {
      var COLORS = ${colorsJson};
      var INTERACTIVE = ${interactive ? 'true' : 'false'};
      var RING_R = 6.5;
      var RING_C = 2 * Math.PI * RING_R;

      document.documentElement.style.setProperty('--pill-bg', COLORS.pillBg);
      document.documentElement.style.setProperty('--pill-text', COLORS.pillText);
      document.documentElement.style.setProperty('--pulse', COLORS.primary);
      document.documentElement.style.setProperty('--user-dot', COLORS.userDot);
      document.documentElement.style.setProperty('--user-halo', COLORS.userHalo);

      function post(payload) {
        if (window.ReactNativeWebView) {
          window.ReactNativeWebView.postMessage(JSON.stringify(payload));
        }
      }

      function fail(code) { post({ type: 'error', code: code }); }
      if (!window.maplibregl) { fail('maplibre-failed-to-load'); return; }

      var map = new maplibregl.Map({
        container: 'map',
        style: ${styleJson},
        center: [${center.lng}, ${center.lat}],
        zoom: ${zoom},
        attributionControl: { compact: true },
        dragRotate: false,
        pitchWithRotate: false,
        touchPitch: false,
      });
      map.touchZoomRotate.disableRotation();

      var markers = {};   // id -> { marker, el, data }
      var selectedId = null;
      var userMarker = null;
      var suppressMoveEvent = false;

      function freshColor(fraction) {
        if (fraction > 0.66) return COLORS.fresh;
        if (fraction > 0.33) return COLORS.aging;
        return COLORS.expiring;
      }

      function fmt(ms) {
        var total = Math.max(0, Math.floor(ms / 1000));
        var m = Math.floor(total / 60);
        var s = total % 60;
        return (m < 10 ? '0' + m : '' + m) + ':' + (s < 10 ? '0' + s : '' + s);
      }

      function markerHtml(data) {
        return (
          '<div class="pk-pulse" style="display:none"></div>' +
          '<div class="pk-pill">' +
            '<svg class="pk-ring" width="18" height="18" viewBox="0 0 18 18">' +
              '<circle cx="9" cy="9" r="' + RING_R + '" fill="none" stroke="' + COLORS.track + '" stroke-width="2"></circle>' +
              '<circle class="pk-arc" cx="9" cy="9" r="' + RING_R + '" fill="none" stroke="' + COLORS.fresh + '" stroke-width="2" stroke-linecap="round" stroke-dasharray="' + RING_C + ' ' + RING_C + '" transform="rotate(-90 9 9)"></circle>' +
            '</svg>' +
            (data.warning ? '<span class="pk-warn">⚠︎</span>' : '') +
            '<span class="pk-time">--:--</span>' +
          '</div>' +
          '<div class="pk-caret"></div>'
        );
      }

      function updateMarkerEl(entry, now) {
        var data = entry.data;
        var el = entry.el;
        var timeEl = el.querySelector('.pk-time');
        var arcEl = el.querySelector('.pk-arc');
        if (!data.live) {
          timeEl.textContent = '—';
          arcEl.setAttribute('stroke', COLORS.muted);
          arcEl.setAttribute('stroke-dashoffset', String(RING_C * 0.25));
          return;
        }
        var created = Date.parse(data.createdAt);
        var expires = Date.parse(data.expiresAt);
        var remaining = expires - now;
        if (remaining <= 0) {
          el.style.display = 'none';
          return;
        }
        el.style.display = '';
        var fraction = Math.max(0, Math.min(1, remaining / Math.max(1, expires - created)));
        timeEl.textContent = fmt(remaining);
        var color = freshColor(fraction);
        timeEl.style.color = color;
        arcEl.setAttribute('stroke', color);
        arcEl.setAttribute('stroke-dashoffset', String(RING_C * (1 - fraction)));
      }

      function setSpots(spots) {
        var seen = {};
        spots.forEach(function (data) {
          seen[data.id] = true;
          var entry = markers[data.id];
          if (!entry) {
            var el = document.createElement('div');
            el.className = 'pk-marker';
            el.innerHTML = markerHtml(data);
            if (INTERACTIVE) {
              el.addEventListener('click', function (event) {
                event.stopPropagation();
                post({ type: 'spotTap', id: data.id });
              });
            }
            var marker = new maplibregl.Marker({ element: el, anchor: 'bottom' })
              .setLngLat([data.lng, data.lat])
              .addTo(map);
            entry = markers[data.id] = { marker: marker, el: el, data: data };
          } else {
            entry.data = data;
            entry.marker.setLngLat([data.lng, data.lat]);
          }
          updateMarkerEl(entry, Date.now());
        });
        Object.keys(markers).forEach(function (id) {
          if (!seen[id]) {
            markers[id].marker.remove();
            delete markers[id];
          }
        });
        applySelection();
      }

      function applySelection() {
        Object.keys(markers).forEach(function (id) {
          var entry = markers[id];
          var isSelected = id === selectedId;
          entry.el.classList.toggle('pk-selected', isSelected);
          entry.el.querySelector('.pk-pulse').style.display = isSelected ? '' : 'none';
          entry.el.style.zIndex = isSelected ? '10' : '1';
        });
      }

      function setUserLocation(loc) {
        if (!loc) {
          if (userMarker) { userMarker.remove(); userMarker = null; }
          return;
        }
        if (!userMarker) {
          var el = document.createElement('div');
          el.className = 'pk-user';
          userMarker = new maplibregl.Marker({ element: el }).setLngLat([loc.lng, loc.lat]).addTo(map);
        } else {
          userMarker.setLngLat([loc.lng, loc.lat]);
        }
      }

      setInterval(function () {
        var now = Date.now();
        Object.keys(markers).forEach(function (id) { updateMarkerEl(markers[id], now); });
      }, 1000);

      map.on('click', function () { post({ type: 'mapTap' }); });
      map.on('moveend', function (event) {
        if (suppressMoveEvent) { suppressMoveEvent = false; return; }
        var c = map.getCenter();
        post({
          type: 'moveEnd',
          lat: c.lat,
          lng: c.lng,
          zoom: map.getZoom(),
          byGesture: Boolean(event.originalEvent),
        });
      });
      map.on('move', function () {
        var c = map.getCenter();
        post({ type: 'move', lat: c.lat, lng: c.lng });
      });
      map.on('load', function () { post({ type: 'ready' }); });
      map.on('error', function () { /* tile errors are non-fatal */ });

      window.__parkio_dispatch = function (json) {
        try {
          var message = typeof json === 'string' ? JSON.parse(json) : json;
          if (message.op === 'setSpots') setSpots(message.spots || []);
          else if (message.op === 'setSelected') { selectedId = message.id || null; applySelection(); }
          else if (message.op === 'flyTo') {
            suppressMoveEvent = Boolean(message.silent);
            map.flyTo({ center: [message.lng, message.lat], zoom: message.zoom || map.getZoom(), duration: 650 });
          }
          else if (message.op === 'jumpTo') {
            suppressMoveEvent = Boolean(message.silent);
            map.jumpTo({ center: [message.lng, message.lat], zoom: message.zoom || map.getZoom() });
          }
          else if (message.op === 'setUserLocation') setUserLocation(message.location);
        } catch (error) {
          fail('dispatch-failed');
        }
      };

      post({ type: 'boot' });
    })();
  </script>
</body>
</html>`;
}
