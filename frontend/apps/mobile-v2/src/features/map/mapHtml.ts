import { buildRasterStyle, type LatLng } from '@parkio/geo';
import {
  MAPLIBRE_RUNTIME_CSS,
  MAPLIBRE_RUNTIME_JS,
  MAPLIBRE_RUNTIME_VERSION,
} from './vendor/maplibreRuntime.generated';
import { safeJsonForHtmlScript } from './safeJsonForHtmlScript';

/**
 * Self-contained HTML document hosting MapLibre GL JS inside a WebView — the
 * isolated map renderer. RN talks to it only through the bridge:
 * `window.__parkio_dispatch(json)` inbound, `ReactNativeWebView.postMessage`
 * outbound.
 *
 * Marker semantics (product language):
 * - Municipal = GREEN P (category color; occupancy is secondary opacity)
 * - Community = BLUE P (authenticated only; freshness ring is secondary chrome)
 * - Destination / user location remain visually distinct
 *
 * Design notes (pen `mob/map`): desaturated OSM raster; DOM markers sized for
 * mobile hit targets; selection uses scale/outline/halo without recoloring
 * category. Result counts stay level-capped (≤50).
 *
 * MapLibre JS/CSS are packaged from npm maplibre-gl (no runtime CDN fetch).
 */

/** Packaged MapLibre GL JS version (see vendor/maplibre-runtime.manifest.json). */
export const MAPLIBRE_VERSION = MAPLIBRE_RUNTIME_VERSION;

export interface MapHtmlColors {
  /** Freshness ramp (secondary chrome on community markers). */
  fresh: string;
  aging: string;
  expiring: string;
  /** Ring track + chrome. */
  track: string;
  pillBg: string;
  pillText: string;
  muted: string;
  /** Selected pulse + user dot + community category (BLUE P). */
  primary: string;
  userDot: string;
  userHalo: string;
  /** Municipal category fill (GREEN P) — theme secondary. */
  municipal: string;
  /** Glyph on municipal fill (white in light, dark ink in dark). */
  municipalGlyph: string;
  /** Glyph on community blue fill. */
  communityGlyph: string;
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
  /** Null while pending moderation — map markers for live spots always have a value. */
  expiresAt: string | null;
  /** Live statuses tick the secondary freshness ring; others mute the ring. */
  live: boolean;
  /** Suspicious flag renders the warning glyph. */
  warning?: boolean;
  /** Screen-reader label (authenticated community context only). */
  accessibilityLabel?: string;
}

/**
 * Municipal facility marker payload — separate inventory from {@link MapSpotMarker}.
 * Never passed through setSpots.
 */
export interface MapMunicipalMarkerPayload {
  id: string;
  lat: number;
  lng: number;
  /** live | aging | stale_live | static | invalid */
  occupancyKind: string;
  accessibilityLabel?: string;
}

function assertFiniteCamera(center: LatLng, zoom: number): { lat: number; lng: number; zoom: number } {
  if (
    typeof center.lat !== 'number' ||
    typeof center.lng !== 'number' ||
    !Number.isFinite(center.lat) ||
    !Number.isFinite(center.lng) ||
    center.lat < -90 ||
    center.lat > 90 ||
    center.lng < -180 ||
    center.lng > 180
  ) {
    throw new Error('buildMapHtml: invalid initial center');
  }
  if (typeof zoom !== 'number' || !Number.isFinite(zoom) || zoom < 0 || zoom > 22) {
    throw new Error('buildMapHtml: invalid initial zoom');
  }
  return { lat: center.lat, lng: center.lng, zoom };
}

export function buildMapHtml(options: MapHtmlOptions): string {
  const { mode, colors } = options;
  const camera = assertFiniteCamera(options.center, options.zoom);
  const style = buildRasterStyle();
  const styleJson = safeJsonForHtmlScript(style);
  const colorsJson = safeJsonForHtmlScript(colors);
  const interactive = options.interactiveSpots !== false;

  const canvasFilter =
    mode === 'dark'
      ? 'invert(1) hue-rotate(200deg) saturate(0.22) brightness(0.82) contrast(0.9)'
      : 'saturate(0.32) contrast(0.94) brightness(1.05)';

  // Local MapLibre runtime — never fetched from a CDN at map startup.
  const maplibreCss = MAPLIBRE_RUNTIME_CSS;
  const maplibreJs = MAPLIBRE_RUNTIME_JS;

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' blob:; style-src 'unsafe-inline'; img-src data: https://a.tile.openstreetmap.org https://b.tile.openstreetmap.org https://c.tile.openstreetmap.org; connect-src https://a.tile.openstreetmap.org https://b.tile.openstreetmap.org https://c.tile.openstreetmap.org; worker-src blob:; child-src blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; navigate-to 'none';" />
  <style>${maplibreCss}</style>
  <script>${maplibreJs}</script>
  <style>
    html, body, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: ${
      mode === 'dark' ? '#0B1626' : '#ECEFF3'
    }; overflow: hidden; }
    .maplibregl-canvas { filter: ${canvasFilter}; }
    .maplibregl-ctrl-attrib { font-size: 9px; opacity: 0.75; }
    .maplibregl-ctrl-bottom-left { display: none; }

    .pk-marker { position: relative; display: flex; flex-direction: column; align-items: center; cursor: pointer; }
    /* Community = BLUE P (primary); freshness ring is secondary chrome. */
    .pk-spot-pin {
      position: relative;
      width: 34px; height: 34px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      background: var(--community-accent); color: var(--community-glyph);
      border: 2.5px solid #ffffff;
      box-shadow: 0 4px 14px rgba(0,0,0,0.16);
    }
    .pk-spot-p {
      font: 700 15px/1 -apple-system, 'Inter', system-ui, sans-serif;
      letter-spacing: -0.02em;
      position: relative; z-index: 1;
    }
    .pk-spot-ring {
      position: absolute; inset: -5px; pointer-events: none;
    }
    .pk-warn {
      position: absolute; top: -4px; right: -4px; z-index: 2;
      font-size: 9px; line-height: 1;
      background: var(--pill-bg); border-radius: 999px;
      padding: 2px 3px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.18);
    }
    .pk-caret {
      width: 0; height: 0; margin-top: -1px;
      border-left: 5px solid transparent; border-right: 5px solid transparent;
      border-top: 6px solid var(--community-accent);
      filter: drop-shadow(0 2px 2px rgba(0,0,0,0.08));
    }
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
    .pk-selected .pk-spot-pin {
      outline: 2px solid var(--pulse);
      transform: scale(1.1);
    }
    .pk-recommended .pk-spot-pin { box-shadow: 0 0 0 3px rgba(0, 128, 105, 0.45); }
    .pk-recommended-top .pk-spot-pin { box-shadow: 0 0 0 4px rgba(0, 128, 105, 0.7); }
    @media (prefers-reduced-motion: reduce) {
      .pk-selected .pk-spot-pin { transform: none; }
    }
    .pk-dest {
      position: relative; display: flex; flex-direction: column; align-items: center;
      pointer-events: none;
    }
    .pk-dest-pin {
      width: 28px; height: 28px; border-radius: 6px; transform: rotate(45deg);
      background: #008069; border: 2px solid #ffffff;
      box-shadow: 0 2px 8px rgba(0,0,0,0.28);
    }
    .pk-dest-label {
      margin-top: 8px; transform: none;
      font: 600 11px/14px system-ui, sans-serif;
      color: var(--pill-text); background: var(--pill-bg);
      padding: 2px 6px; border-radius: 6px;
      max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      box-shadow: 0 1px 4px rgba(0,0,0,0.18);
    }
    .pk-parked {
      position: relative; display: flex; flex-direction: column; align-items: center;
      pointer-events: none; z-index: 14;
    }
    .pk-parked-pin {
      width: 28px; height: 28px; border-radius: 50%;
      background: #0F766E; border: 3px solid #ffffff;
      box-shadow: 0 2px 10px rgba(15,118,110,0.45);
    }
    .pk-parked-label {
      margin-top: 6px;
      font: 600 11px/14px system-ui, sans-serif;
      color: var(--pill-text); background: var(--pill-bg);
      padding: 2px 6px; border-radius: 6px;
      max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      box-shadow: 0 1px 4px rgba(0,0,0,0.18);
    }
    .pk-muni-recommended .pk-muni-pin { box-shadow: 0 0 0 3px rgba(0, 128, 105, 0.45); }
    .pk-muni-recommended-top .pk-muni-pin { box-shadow: 0 0 0 4px rgba(0, 128, 105, 0.7); }

    .pk-user { width: 16px; height: 16px; border-radius: 50%; background: var(--user-dot);
      border: 3px solid #fff; box-shadow: 0 0 0 6px var(--user-halo), 0 2px 6px rgba(0,0,0,0.25); }

    /* Municipal = GREEN P (category); occupancy kind only adjusts secondary opacity. */
    .pk-muni {
      position: relative; display: flex; flex-direction: column; align-items: center; cursor: pointer;
      --muni-accent: ${colors.municipal};
    }
    .pk-muni-pin {
      width: 34px; height: 34px; border-radius: 10px;
      display: flex; align-items: center; justify-content: center;
      background: var(--muni-accent); color: var(--muni-glyph);
      border: 2.5px solid #ffffff;
      box-shadow: 0 4px 14px rgba(0,0,0,0.16);
    }
    .pk-muni-p {
      font: 700 15px/1 -apple-system, 'Inter', system-ui, sans-serif;
      letter-spacing: -0.02em;
    }
    .pk-muni-caret {
      width: 0; height: 0; margin-top: -1px;
      border-left: 5px solid transparent; border-right: 5px solid transparent;
      border-top: 6px solid var(--muni-accent);
      filter: drop-shadow(0 2px 2px rgba(0,0,0,0.08));
    }
    .pk-muni-kind-stale_live { opacity: 0.88; }
    .pk-muni-kind-static, .pk-muni-kind-invalid { opacity: 0.72; }
    .pk-muni-selected .pk-muni-pin {
      outline: 2px solid var(--pulse);
      transform: scale(1.1);
    }
    .pk-muni-selected .pk-muni-pulse { display: block !important; }
    .pk-muni-pulse {
      display: none; position: absolute; top: 50%; left: 50%;
      width: 52px; height: 52px; margin: -32px 0 0 -26px;
      border-radius: 12px; border: 1.5px solid var(--pulse); opacity: 0.45;
      pointer-events: none;
      animation: pk-pulse 2.2s ease-out infinite;
    }
    @media (prefers-reduced-motion: reduce) {
      .pk-muni-pulse { animation: none; }
      .pk-muni-selected .pk-muni-pin { transform: none; }
    }
  </style>
</head>
<body>
  <div id="map"></div>
  <script>
    (function () {
      var COLORS = ${colorsJson};
      var INTERACTIVE = ${interactive ? 'true' : 'false'};
      var RING_R = 15;
      var RING_C = 2 * Math.PI * RING_R;

      document.documentElement.style.setProperty('--pill-bg', COLORS.pillBg);
      document.documentElement.style.setProperty('--pill-text', COLORS.pillText);
      document.documentElement.style.setProperty('--pulse', COLORS.primary);
      document.documentElement.style.setProperty('--user-dot', COLORS.userDot);
      document.documentElement.style.setProperty('--user-halo', COLORS.userHalo);
      document.documentElement.style.setProperty('--community-accent', COLORS.fresh);
      document.documentElement.style.setProperty('--community-glyph', COLORS.communityGlyph);
      document.documentElement.style.setProperty('--muni-glyph', COLORS.municipalGlyph);

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
        center: [${camera.lng}, ${camera.lat}],
        zoom: ${camera.zoom},
        attributionControl: { compact: true },
        dragRotate: false,
        pitchWithRotate: false,
        touchPitch: false,
      });
      map.touchZoomRotate.disableRotation();

      var markers = {};   // id -> { marker, el, data }  (community spots)
      var muniMarkers = {}; // id -> { marker, el, data } (municipal facilities)
      var selectedId = null;
      var selectedMunicipalId = null;
      var recommendedCommunity = {}; // id -> true
      var recommendedMunicipal = {}; // id -> true
      var topCommunityId = null;
      var topMunicipalId = null;
      var destinationMarker = null;
      var parkedCarMarker = null;
      var userMarker = null;
      var suppressMoveEvent = false;

      function freshColor(fraction) {
        if (fraction > 0.66) return COLORS.fresh;
        if (fraction > 0.33) return COLORS.aging;
        return COLORS.expiring;
      }

      function markerHtml(data) {
        return (
          '<div class="pk-pulse" style="display:none"></div>' +
          '<div class="pk-spot-pin" aria-hidden="true">' +
            '<svg class="pk-spot-ring" width="44" height="44" viewBox="0 0 44 44">' +
              '<circle cx="22" cy="22" r="' + RING_R + '" fill="none" stroke="' + COLORS.track + '" stroke-width="2.5"></circle>' +
              '<circle class="pk-arc" cx="22" cy="22" r="' + RING_R + '" fill="none" stroke="' + COLORS.fresh + '" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="' + RING_C + ' ' + RING_C + '" transform="rotate(-90 22 22)"></circle>' +
            '</svg>' +
            '<span class="pk-spot-p">P</span>' +
            (data.warning ? '<span class="pk-warn">⚠︎</span>' : '') +
          '</div>' +
          '<div class="pk-caret"></div>'
        );
      }

      function updateMarkerEl(entry, now) {
        var data = entry.data;
        var el = entry.el;
        var arcEl = el.querySelector('.pk-arc');
        if (!arcEl) return;
        if (!data.live || !data.expiresAt) {
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
        var color = freshColor(fraction);
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
            if (typeof data.accessibilityLabel === 'string' && data.accessibilityLabel) {
              el.setAttribute('role', 'button');
              el.setAttribute('aria-label', data.accessibilityLabel);
            }
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
            if (typeof data.accessibilityLabel === 'string' && data.accessibilityLabel) {
              entry.el.setAttribute('aria-label', data.accessibilityLabel);
            }
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
          var isRecommended = Boolean(recommendedCommunity[id]);
          var isTop = id === topCommunityId;
          entry.el.classList.toggle('pk-selected', isSelected);
          entry.el.classList.toggle('pk-recommended', isRecommended);
          entry.el.classList.toggle('pk-recommended-top', isTop);
          entry.el.querySelector('.pk-pulse').style.display = isSelected ? '' : 'none';
          entry.el.style.zIndex = isSelected ? '10' : isTop ? '8' : isRecommended ? '6' : '1';
        });
        Object.keys(muniMarkers).forEach(function (id) {
          var entry = muniMarkers[id];
          var isSelected = id === selectedMunicipalId;
          var isRecommended = Boolean(recommendedMunicipal[id]);
          var isTop = id === topMunicipalId;
          entry.el.classList.toggle('pk-muni-selected', isSelected);
          entry.el.classList.toggle('pk-muni-recommended', isRecommended);
          entry.el.classList.toggle('pk-muni-recommended-top', isTop);
          entry.el.style.zIndex = isSelected ? '11' : isTop ? '9' : isRecommended ? '7' : '2';
        });
      }

      function setDestinationMarker(marker) {
        if (destinationMarker) {
          destinationMarker.remove();
          destinationMarker = null;
        }
        if (!marker || typeof marker.lat !== 'number' || typeof marker.lng !== 'number') return;
        if (!isFinite(marker.lat) || !isFinite(marker.lng)) return;
        var el = document.createElement('div');
        el.className = 'pk-dest';
        var label = typeof marker.label === 'string' ? marker.label : '';
        el.setAttribute('role', 'img');
        if (label) el.setAttribute('aria-label', label);
        el.innerHTML =
          '<div class="pk-dest-pin" aria-hidden="true"></div>' +
          (label
            ? '<div class="pk-dest-label">' +
              String(label)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;') +
              '</div>'
            : '');
        destinationMarker = new maplibregl.Marker({ element: el, anchor: 'center' })
          .setLngLat([marker.lng, marker.lat])
          .addTo(map);
      }

      function setParkedCarMarker(marker) {
        if (parkedCarMarker) {
          parkedCarMarker.remove();
          parkedCarMarker = null;
        }
        if (!marker || typeof marker.lat !== 'number' || typeof marker.lng !== 'number') return;
        if (!isFinite(marker.lat) || !isFinite(marker.lng)) return;
        var el = document.createElement('div');
        el.className = 'pk-parked';
        var label = typeof marker.label === 'string' ? marker.label : '';
        el.setAttribute('role', 'img');
        if (label) el.setAttribute('aria-label', label);
        el.innerHTML =
          '<div class="pk-parked-pin" aria-hidden="true"></div>' +
          (label
            ? '<div class="pk-parked-label">' +
              String(label)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;') +
              '</div>'
            : '');
        parkedCarMarker = new maplibregl.Marker({ element: el, anchor: 'center' })
          .setLngLat([marker.lng, marker.lat])
          .addTo(map);
      }

      function setRecommendedHighlights(payload) {
        recommendedCommunity = {};
        recommendedMunicipal = {};
        topCommunityId = null;
        topMunicipalId = null;
        if (payload) {
          (payload.communityIds || []).forEach(function (id) {
            if (typeof id === 'string') recommendedCommunity[id] = true;
          });
          (payload.municipalIds || []).forEach(function (id) {
            if (typeof id === 'string') recommendedMunicipal[id] = true;
          });
          if (typeof payload.topCommunityId === 'string') topCommunityId = payload.topCommunityId;
          if (typeof payload.topMunicipalId === 'string') topMunicipalId = payload.topMunicipalId;
        }
        applySelection();
      }

      function muniKindClass(kind) {
        var allowed = {
          live: 1, aging: 1, stale_live: 1, static: 1, invalid: 1
        };
        return allowed[kind] ? kind : 'static';
      }

      function muniMarkerHtml(kind) {
        return (
          '<div class="pk-muni-pulse"></div>' +
          '<div class="pk-muni-pin pk-muni-kind-' + muniKindClass(kind) + '" aria-hidden="true">' +
            '<span class="pk-muni-p">P</span>' +
          '</div>' +
          '<div class="pk-muni-caret"></div>'
        );
      }

      function setMunicipalFacilities(facilities) {
        var seen = {};
        (facilities || []).forEach(function (data) {
          if (!data || typeof data.id !== 'string') return;
          if (typeof data.lat !== 'number' || typeof data.lng !== 'number') return;
          if (!isFinite(data.lat) || !isFinite(data.lng)) return;
          seen[data.id] = true;
          var entry = muniMarkers[data.id];
          var kind = typeof data.occupancyKind === 'string' ? data.occupancyKind : 'static';
          if (!entry) {
            var el = document.createElement('div');
            el.className = 'pk-muni';
            el.innerHTML = muniMarkerHtml(kind);
            if (typeof data.accessibilityLabel === 'string' && data.accessibilityLabel) {
              el.setAttribute('role', 'button');
              el.setAttribute('aria-label', data.accessibilityLabel);
            }
            if (INTERACTIVE) {
              el.addEventListener('click', function (event) {
                event.stopPropagation();
                post({ type: 'municipalTap', id: data.id });
              });
            }
            var marker = new maplibregl.Marker({ element: el, anchor: 'bottom' })
              .setLngLat([data.lng, data.lat])
              .addTo(map);
            entry = muniMarkers[data.id] = { marker: marker, el: el, data: data };
          } else {
            entry.data = data;
            entry.marker.setLngLat([data.lng, data.lat]);
            var pin = entry.el.querySelector('.pk-muni-pin');
            if (pin) {
              pin.className = 'pk-muni-pin pk-muni-kind-' + muniKindClass(kind);
            }
            if (typeof data.accessibilityLabel === 'string' && data.accessibilityLabel) {
              entry.el.setAttribute('aria-label', data.accessibilityLabel);
            }
          }
        });
        Object.keys(muniMarkers).forEach(function (id) {
          if (!seen[id]) {
            muniMarkers[id].marker.remove();
            delete muniMarkers[id];
          }
        });
        applySelection();
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
          else if (message.op === 'setMunicipalFacilities') setMunicipalFacilities(message.facilities || []);
          else if (message.op === 'setSelectedMunicipal') {
            selectedMunicipalId = message.id || null;
            applySelection();
          }
          else if (message.op === 'setDestinationMarker') setDestinationMarker(message.marker || null);
          else if (message.op === 'setParkedCarMarker') setParkedCarMarker(message.marker || null);
          else if (message.op === 'setRecommendedHighlights') setRecommendedHighlights(message.payload || null);
          else if (message.op === 'flyTo') {
            suppressMoveEvent = Boolean(message.silent);
            map.flyTo({ center: [message.lng, message.lat], zoom: message.zoom || map.getZoom(), duration: 650 });
          }
          else if (message.op === 'jumpTo') {
            suppressMoveEvent = Boolean(message.silent);
            map.jumpTo({ center: [message.lng, message.lat], zoom: message.zoom || map.getZoom() });
          }
          else if (message.op === 'fitBounds') {
            suppressMoveEvent = Boolean(message.silent);
            var pad = message.padding || { top: 80, bottom: 80, left: 48, right: 48 };
            var bounds = new maplibregl.LngLatBounds(
              [message.west, message.south],
              [message.east, message.north]
            );
            map.fitBounds(bounds, {
              padding: pad,
              maxZoom: typeof message.maxZoom === 'number' ? message.maxZoom : 15,
              duration: 700,
            });
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
