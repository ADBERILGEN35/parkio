/**
 * Builders for a self-contained MapLibre raster basemap that needs no API key.
 * Shared by the web map (MapLibre GL JS) and the mobile WebView map so both use
 * an identical tile source. The return shape is intentionally a plain object
 * (structurally a MapLibre `StyleSpecification`) to avoid a `maplibre-gl`
 * dependency in this framework-agnostic package.
 */

export const DEFAULT_RASTER_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

export const DEFAULT_OSM_ATTRIBUTION = '© OpenStreetMap contributors';

export interface RasterStyleOptions {
  /** Tile template; supports the `{s}` subdomain token (expanded to a/b/c). */
  tileUrl?: string;
  attribution?: string;
  maxzoom?: number;
}

/** MapLibre raster style object (minimal, JSON-serializable). */
export interface RasterStyle {
  version: 8;
  sources: Record<
    string,
    {
      type: 'raster';
      tiles: string[];
      tileSize: number;
      attribution: string;
      maxzoom: number;
    }
  >;
  layers: { id: string; type: 'raster'; source: string }[];
}

/** Expand a `{s}` subdomain template into one URL per subdomain (a/b/c). */
export function expandSubdomains(template: string): string[] {
  if (!template.includes('{s}')) return [template];
  return ['a', 'b', 'c'].map((sub) => template.replace('{s}', sub));
}

/** Build a key-free OSM raster style. */
export function buildRasterStyle(options: RasterStyleOptions = {}): RasterStyle {
  const tileUrl = options.tileUrl ?? DEFAULT_RASTER_TILE_URL;
  const attribution = options.attribution ?? DEFAULT_OSM_ATTRIBUTION;
  const maxzoom = options.maxzoom ?? 19;
  return {
    version: 8,
    sources: {
      'osm-raster': {
        type: 'raster',
        tiles: expandSubdomains(tileUrl),
        tileSize: 256,
        attribution,
        maxzoom,
      },
    },
    layers: [{ id: 'osm-raster', type: 'raster', source: 'osm-raster' }],
  };
}
