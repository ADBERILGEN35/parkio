import { buildMapHtml } from '../webmap/mapHtml';

const colors = {
  success: '#0a0',
  warning: '#fa0',
  danger: '#f00',
  muted: '#999',
  primary: '#0050CB',
  onPrimary: '#fff',
  clusterFill: '#0050CB',
  clusterText: '#fff',
  userDot: '#0050CB',
  userHalo: '#0050CB',
};

describe('buildMapHtml', () => {
  it('embeds the initial camera and a glyphs endpoint for cluster-count text', () => {
    const html = buildMapHtml({
      center: { lat: 38.4187, lng: 27.1283 },
      zoom: 15,
      colorScheme: 'light',
      colors,
    });

    expect(html).toContain('center: [27.1283, 38.4187]');
    expect(html).toContain('zoom: 15');
    // Symbol layers need a glyphs source to render the cluster count numbers.
    expect(html).toContain('fonts.openmaptiles.org');
    expect(html).toContain("'text-font': ['Noto Sans Regular']");
  });

  it('declares native clustering and a tone-driven color match expression', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(html).toContain('cluster: true');
    expect(html).toContain("['match', ['get', 'tone']");
    expect(html).toContain(colors.success);
    expect(html).toContain(colors.danger);
  });

  it('applies a dark basemap filter only in dark mode', () => {
    const dark = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'dark', colors });
    const light = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(dark).toContain('invert(1)');
    expect(light).not.toContain('invert(1)');
  });

  it('exposes the inbound bridge the React Native side drives', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(html).toContain('window.__parkio');
    expect(html).toContain('setSpots');
    expect(html).toContain('setUserLocation');
    expect(html).toContain('setCamera');
    expect(html).toContain('fitBounds');
    expect(html).toContain('dispose');
    expect(html).toContain('map.remove()');
  });

  it('keeps tile fetch failures non-fatal but propagates renderer errors', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(html).toContain("message.indexOf('Failed to fetch') === -1");
    expect(html).toContain("post({ type: 'error', reason: message })");
  });

  it('applies pan-performance map flags', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(html).toContain('fadeDuration: 0');
    expect(html).toContain('renderWorldCopies: false');
    expect(html).toContain('refreshExpiredTiles: false');
    expect(html).toContain('maxTileCacheSize');
  });

  it('guards region emission against unchanged camera (no per-frame spam)', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    // Region is emitted on moveend only, and skipped when the key is unchanged.
    expect(html).toContain("map.on('moveend'");
    expect(html).toContain('if (key === lastRegionKey) return;');
    // No continuous 'move' listener that would post on every frame.
    expect(html).not.toContain("map.on('move',");
  });

  it('embeds telemetry counters emitted at most once per second when enabled', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(html).toContain("type: 'telemetry'");
    expect(html).toContain('setInterval(emitTelemetry, 1000)');
    expect(html).toContain('setTelemetry');
    // The render-counter hook gives true repaint FPS.
    expect(html).toContain("map.on('render'");
  });

  it('embeds the frame-time bench harness that posts RAW frames (Hermes-safe)', () => {
    const html = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    expect(html).toContain('window.__parkioBench');
    expect(html).toContain('requestAnimationFrame');
    // Stats are computed on the RN side; the WebView posts raw frame intervals.
    expect(html).toContain("type: 'bench', frames: frames");
    // Must NOT rely on Function.prototype.toString (returns bytecode under Hermes).
    expect(html).not.toContain('computeFrameStats.toString');
    expect(html).not.toContain('[bytecode]');
  });

  it('can disable clustering for the marker-density benchmark', () => {
    const clustered = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors });
    const flat = buildMapHtml({ center: { lat: 0, lng: 0 }, zoom: 12, colorScheme: 'light', colors, cluster: false });
    expect(clustered).toContain('cluster: true');
    expect(flat).toContain('cluster: false');
  });
});
