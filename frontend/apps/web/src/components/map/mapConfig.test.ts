import type { StyleSpecification } from 'maplibre-gl';
import { afterEach, describe, expect, it, vi } from 'vitest';

// mapConfig reads import.meta.env at module-eval time, so each scenario stubs the
// env and re-imports a fresh module instance.
async function loadMapConfig() {
  vi.resetModules();
  return import('./mapConfig');
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('getMapStyle (MapTiler integration)', () => {
  it('builds the MapTiler vector style URL when a key is set', async () => {
    vi.stubEnv('VITE_MAPTILER_KEY', 'test-key-123');
    const { getMapStyle, hasMapTilerKey } = await loadMapConfig();

    expect(hasMapTilerKey).toBe(true);
    expect(getMapStyle()).toBe(
      'https://api.maptiler.com/maps/streets-v2/style.json?key=test-key-123',
    );
  });

  it('honors a custom MapTiler style id (style switching readiness)', async () => {
    vi.stubEnv('VITE_MAPTILER_KEY', 'k');
    vi.stubEnv('VITE_MAPTILER_STYLE', 'streets-v2-dark');
    const { getMapStyle } = await loadMapConfig();

    expect(getMapStyle()).toBe('https://api.maptiler.com/maps/streets-v2-dark/style.json?key=k');
    // The function param overrides the env default.
    expect(getMapStyle('basic-v2')).toContain('/maps/basic-v2/style.json?key=k');
  });

  it('falls back to an OSM raster style with attribution when no key is set', async () => {
    vi.stubEnv('VITE_MAPTILER_KEY', '');
    const { getMapStyle, hasMapTilerKey, OSM_ATTRIBUTION } = await loadMapConfig();

    expect(hasMapTilerKey).toBe(false);
    const style = getMapStyle() as StyleSpecification;
    expect(style.version).toBe(8);
    const source = style.sources['osm-raster'] as { type: string; tiles: string[]; attribution: string };
    expect(source.type).toBe('raster');
    expect(source.tiles[0]).toContain('tile.openstreetmap.org');
    expect(source.attribution).toBe(OSM_ATTRIBUTION);
    expect(OSM_ATTRIBUTION).toContain('OpenStreetMap');
  });

  it('expands a {s} subdomain template in the raster fallback override', async () => {
    vi.stubEnv('VITE_MAPTILER_KEY', '');
    vi.stubEnv('VITE_MAP_TILE_URL', 'https://{s}.tile.example.com/{z}/{x}/{y}.png');
    const { getMapStyle } = await loadMapConfig();

    const style = getMapStyle() as StyleSpecification;
    const tiles = (style.sources['osm-raster'] as { tiles: string[] }).tiles;
    expect(tiles).toEqual([
      'https://a.tile.example.com/{z}/{x}/{y}.png',
      'https://b.tile.example.com/{z}/{x}/{y}.png',
      'https://c.tile.example.com/{z}/{x}/{y}.png',
    ]);
  });
});

describe('isValidLatLng', () => {
  it('accepts a finite coordinate pair and rejects partial/invalid input', async () => {
    const { isValidLatLng } = await loadMapConfig();
    expect(isValidLatLng(41.0, 29.0)).toBe(true);
    expect(isValidLatLng(0, 0)).toBe(true);
    expect(isValidLatLng(null, 29)).toBe(false);
    expect(isValidLatLng(41, undefined)).toBe(false);
    expect(isValidLatLng(Number.NaN, 29)).toBe(false);
  });
});
