import { buildRasterStyle, type LatLng } from '@parkio/geo';

/**
 * Builds the self-contained HTML document that hosts the MapLibre GL JS map
 * inside a {@link react-native-webview}. This is the isolated map *renderer*:
 * the React Native side talks to it only through the small bridge defined here
 * (`window.__parkio.*` inbound, `ReactNativeWebView.postMessage` outbound), so
 * the renderer could be swapped (e.g. to a native MapLibre view) without
 * touching the rest of the feature.
 *
 * No API key is used: the basemap is the shared key-free OSM raster style from
 * {@link @parkio/geo}. Clustering, marker styling and hit-testing all run in
 * MapLibre on the GPU, so thousands of points stay at 60fps with zero React
 * re-renders.
 */

/** MapLibre GL JS version — matches the web app's `maplibre-gl` dependency. */
const MAPLIBRE_VERSION = '4.7.1';

export interface MapHtmlColors {
  /** Marker fill per presentation tone (from the app theme). */
  success: string;
  warning: string;
  danger: string;
  muted: string;
  /** Selected-marker ring + cluster color. */
  primary: string;
  onPrimary: string;
  /** Cluster bubble fill + text. */
  clusterFill: string;
  clusterText: string;
  /** User location dot. */
  userDot: string;
  userHalo: string;
}

export interface MapHtmlOptions {
  center: LatLng;
  zoom: number;
  colorScheme: 'light' | 'dark';
  colors: MapHtmlColors;
  /** Raster tile endpoint override (defaults to OSM in @parkio/geo). */
  tileUrl?: string;
  attribution?: string;
  /** Cluster spot markers (default true). Benchmark toggles this to isolate
   * clustering cost from raw marker-count cost. */
  cluster?: boolean;
}

/** Build the MapLibre map HTML document as a string for the WebView `source`. */
export function buildMapHtml(options: MapHtmlOptions): string {
  const { center, zoom, colorScheme, colors } = options;
  const cluster = options.cluster !== false;
  // Add a free glyphs endpoint so symbol layers (cluster counts) can render text.
  const style = {
    ...buildRasterStyle({ tileUrl: options.tileUrl, attribution: options.attribution }),
    glyphs: 'https://fonts.openmaptiles.org/{fontstack}/{range}.pbf',
  };
  const styleJson = JSON.stringify(style);
  const colorsJson = JSON.stringify(colors);
  // OSM raster tiles are light; approximate a dark basemap with a canvas filter.
  const darkFilter =
    colorScheme === 'dark'
      ? 'filter: invert(1) hue-rotate(180deg) brightness(0.92) contrast(0.95);'
      : '';

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link href="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.css" rel="stylesheet" />
  <script src="https://unpkg.com/maplibre-gl@${MAPLIBRE_VERSION}/dist/maplibre-gl.js"></script>
  <style>
    html, body, #map { margin: 0; padding: 0; height: 100%; width: 100%; background: transparent; }
    /* Dark approximation applied to tiles only, so markers keep true colors. */
    .maplibregl-canvas { ${darkFilter} }
    .maplibregl-ctrl-attrib { font-size: 10px; }
    #fallback { position:absolute; inset:0; display:none; align-items:center; justify-content:center;
      font-family: -apple-system, Roboto, sans-serif; color:#5B6B7F; padding:24px; text-align:center; }
  </style>
</head>
<body>
  <div id="map"></div>
  <div id="fallback">Map failed to load. Check your connection and retry.</div>
  <script>
    (function () {
      var COLORS = ${colorsJson};

      // ---- Telemetry: cheap counters, emitted at most once/second and only
      // when explicitly enabled (the bench harness / dev overlay turns it on).
      // Production runs leave it off, so telemetry never adds bridge traffic. ----
      var tel = { outbound: 0, injected: 0, region: 0, render: 0, error: 0 };
      var telTimer = null;
      var rawPost = function (msg) {
        if (window.ReactNativeWebView) {
          window.ReactNativeWebView.postMessage(JSON.stringify(msg));
        }
      };
      var post = function (msg) { tel.outbound++; rawPost(msg); };
      function emitTelemetry() {
        // rawPost (not post) so the telemetry frame never counts itself.
        rawPost({
          type: 'telemetry',
          outbound: tel.outbound, injected: tel.injected,
          region: tel.region, render: tel.render, error: tel.error
        });
        tel.outbound = 0; tel.injected = 0; tel.region = 0; tel.render = 0; tel.error = 0;
      }
      function setTelemetry(on) {
        if (telTimer) { clearInterval(telTimer); telTimer = null; }
        if (on) telTimer = setInterval(emitTelemetry, 1000);
      }
      function fail(reason) {
        tel.error++;
        document.getElementById('fallback').style.display = 'flex';
        post({ type: 'error', reason: String(reason) });
      }
      // Surface any uncaught WebView error to React Native so renderer failures
      // are observable instead of silently dying inside the WebView.
      window.onerror = function (message, source, line, col) {
        tel.error++;
        rawPost({ type: 'error', reason: 'onerror: ' + String(message) + ' @' + line + ':' + col });
        return false;
      };
      if (!window.maplibregl) { fail('maplibre-failed-to-load'); return; }

      var map;
      try {
        map = new maplibregl.Map({
          container: 'map',
          style: ${styleJson},
          center: [${center.lng}, ${center.lat}],
          zoom: ${zoom},
          attributionControl: { compact: true },
          dragRotate: false,
          pitchWithRotate: false,
          // ---- Pan-perf flags ----
          // No symbol cross-fade: every moveend otherwise repaints for ~300ms
          // fading cluster-count labels, which shows up directly as post-pan jank.
          fadeDuration: 0,
          // We render a single world; copies multiply tiles drawn per frame.
          renderWorldCopies: false,
          // Don't re-request tiles on HTTP expiry mid-interaction.
          refreshExpiredTiles: false,
          // Bound the tile cache so memory (PSS) doesn't grow unboundedly while panning.
          maxTileCacheSize: 96,
        });
      } catch (e) { fail(e); return; }

      map.on('error', function (e) {
        var message = e && e.error && e.error.message ? e.error.message : '';
        if (message && message.indexOf('Failed to fetch') === -1) { tel.error++; post({ type: 'error', reason: message }); }
      });

      // Each 'render' is one repaint; counting them per second yields the
      // renderer's true repaint FPS during a pan, independent of the RN thread.
      map.on('render', function () { tel.render++; });

      function emptyFC() { return { type: 'FeatureCollection', features: [] }; }

      function regionPayload() {
        var c = map.getCenter();
        var b = map.getBounds();
        return {
          type: 'region',
          center: { lat: c.lat, lng: c.lng },
          zoom: map.getZoom(),
          bounds: {
            north: b.getNorth(), south: b.getSouth(),
            east: b.getEast(), west: b.getWest()
          }
        };
      }

      map.on('load', function () {
        map.addSource('spots', {
          type: 'geojson',
          data: emptyFC(),
          cluster: ${cluster ? 'true' : 'false'},
          clusterRadius: 48,
          clusterMaxZoom: 16
        });

        // Cluster bubbles, sized by point_count.
        map.addLayer({
          id: 'clusters', type: 'circle', source: 'spots', filter: ['has', 'point_count'],
          paint: {
            'circle-color': COLORS.clusterFill,
            'circle-stroke-color': COLORS.onPrimary,
            'circle-stroke-width': 2,
            'circle-radius': ['step', ['get', 'point_count'], 18, 10, 24, 50, 30]
          }
        });
        map.addLayer({
          id: 'cluster-count', type: 'symbol', source: 'spots', filter: ['has', 'point_count'],
          layout: {
            'text-field': ['get', 'point_count_abbreviated'],
            'text-size': 13, 'text-font': ['Noto Sans Regular']
          },
          paint: { 'text-color': COLORS.clusterText }
        });

        // Selected-spot highlight ring (drawn under the dot).
        map.addLayer({
          id: 'selected-ring', type: 'circle', source: 'spots',
          filter: ['all', ['!', ['has', 'point_count']], ['==', ['get', 'selected'], true]],
          paint: {
            'circle-radius': 16, 'circle-color': COLORS.primary, 'circle-opacity': 0.25,
            'circle-stroke-color': COLORS.primary, 'circle-stroke-width': 2
          }
        });

        // Unclustered spot dots, colored by presentation tone.
        map.addLayer({
          id: 'spot-points', type: 'circle', source: 'spots', filter: ['!', ['has', 'point_count']],
          paint: {
            'circle-radius': 9,
            'circle-stroke-width': 2,
            'circle-stroke-color': COLORS.onPrimary,
            'circle-color': ['match', ['get', 'tone'],
              'success', COLORS.success,
              'warning', COLORS.warning,
              'danger', COLORS.danger,
              COLORS.muted
            ]
          }
        });

        // User location dot + halo.
        map.addSource('user', { type: 'geojson', data: emptyFC() });
        map.addLayer({
          id: 'user-halo', type: 'circle', source: 'user',
          paint: { 'circle-radius': 18, 'circle-color': COLORS.userHalo, 'circle-opacity': 0.25 }
        });
        map.addLayer({
          id: 'user-dot', type: 'circle', source: 'user',
          paint: {
            'circle-radius': 7, 'circle-color': COLORS.userDot,
            'circle-stroke-width': 3, 'circle-stroke-color': COLORS.onPrimary
          }
        });

        map.on('click', 'clusters', function (e) {
          var f = e.features[0];
          map.getSource('spots').getClusterExpansionZoom(f.properties.cluster_id).then(function (z) {
            map.easeTo({ center: f.geometry.coordinates, zoom: z });
          }).catch(function () {});
        });
        map.on('click', 'spot-points', function (e) {
          if (e.features && e.features[0]) {
            post({ type: 'spotPress', spotId: e.features[0].properties.id });
          }
        });
        map.on('click', function (e) {
          var hits = map.queryRenderedFeatures(e.point, { layers: ['clusters', 'spot-points'] });
          if (!hits.length) post({ type: 'mapPress' });
        });
        var setCursor = function (c) { return function () { map.getCanvas().style.cursor = c; }; };
        map.on('mouseenter', 'clusters', setCursor('pointer'));
        map.on('mouseleave', 'clusters', setCursor(''));
        map.on('mouseenter', 'spot-points', setCursor('pointer'));
        map.on('mouseleave', 'spot-points', setCursor(''));

        // Region is emitted only on moveend (never per-frame during the pan),
        // and an unchanged-guard drops jitter where the camera settles back on
        // the same center/zoom, so RN never re-renders for a no-op move.
        var lastRegionKey = null;
        map.on('moveend', function () {
          var p = regionPayload();
          var key = p.center.lat.toFixed(5) + ',' + p.center.lng.toFixed(5) + ',' + p.zoom.toFixed(3);
          if (key === lastRegionKey) return;
          lastRegionKey = key;
          tel.region++;
          post(p);
        });
        post({ type: 'ready' });
      });

      // ---- Inbound bridge (called via injectJavaScript from React Native) ----
      window.__parkio = {
        setSpots: function (geojson) {
          tel.injected++;
          var s = map && map.getSource('spots'); if (s) s.setData(geojson);
        },
        setUserLocation: function (loc) {
          tel.injected++;
          var s = map && map.getSource('user'); if (!s) return;
          s.setData(loc ? { type: 'FeatureCollection', features: [
            { type: 'Feature', geometry: { type: 'Point', coordinates: [loc.lng, loc.lat] }, properties: {} }
          ] } : emptyFC());
        },
        setCamera: function (center, zoom, animate) {
          tel.injected++;
          if (!map) return;
          var opts = { center: [center.lng, center.lat] };
          if (typeof zoom === 'number') opts.zoom = zoom;
          if (animate) map.easeTo(opts); else map.jumpTo(opts);
        },
        fitBounds: function (b, padding) {
          tel.injected++;
          if (!map) return;
          map.fitBounds([[b.west, b.south], [b.east, b.north]], { padding: padding || 64, maxZoom: 16 });
        },
        setTelemetry: function (on) { tel.injected++; setTelemetry(!!on); },
        dispose: function () {
          tel.injected++;
          setTelemetry(false);
          if (!map) return;
          try { map.remove(); } catch (e) {}
          map = null;
        }
      };

      // ---- Benchmark harness (dev-only; driven from the MapBench screen) ----
      // Runs a scripted left/right pan sweep for durationMs while sampling
      // requestAnimationFrame deltas (one sample per *rendered* frame), then posts
      // the RAW frame intervals back to React Native, which computes the
      // percentile/jank stats with the shared, unit-tested computeFrameStats.
      //
      // NB: we intentionally do NOT embed computeFrameStats here via
      // Function.prototype.toString(). Under Hermes (the app's JS engine)
      // toString() returns a compiled-bytecode placeholder rather than the real
      // source, so an embedded copy would throw at call time. Posting raw frames
      // keeps the math in one tested place and Hermes-safe.
      window.__parkioBench = {
        run: function (opts) {
         try {
          opts = opts || {};
          var durationMs = opts.durationMs || 3000;
          var threshold = opts.thresholdMs || 16.7;
          if (!map) { post({ type: 'bench', error: 'map-not-ready' }); return; }
          var frames = [];
          var last = null;
          var running = true;
          function sample(t) {
            if (last !== null) frames.push(t - last);
            last = t;
            if (running) requestAnimationFrame(sample);
          }
          requestAnimationFrame(sample);
          var start = performance.now();
          var origin = map.getCenter();
          function step() {
            var elapsed = performance.now() - start;
            if (elapsed >= durationMs) {
              running = false;
              try { map.jumpTo({ center: origin }); } catch (e) {}
              post({ type: 'bench', frames: frames, thresholdMs: threshold });
              return;
            }
            // Sweep right for 500ms, left for 500ms: realistic panning that
            // loads fresh tiles at the leading edge and returns near origin.
            var dir = (Math.floor(elapsed / 500) % 2 === 0) ? 1 : -1;
            map.panBy([dir * 12, 0], { duration: 0 });
            setTimeout(step, 16);
          }
          step();
         } catch (err) {
          post({ type: 'bench', error: String(err) });
         }
        }
      };
    })();
  </script>
</body>
</html>`;
}
