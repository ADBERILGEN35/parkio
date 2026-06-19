import type { StyleSpecification } from 'maplibre-gl';

/**
 * Centralized map configuration.
 *
 * The map renders MapLibre GL JS. The vector basemap is served by MapTiler when
 * {@link VITE_MAPTILER_KEY} is provided; otherwise it falls back to OpenStreetMap
 * raster tiles so local/dev (and any environment without a key) keeps a working
 * map instead of a blank canvas. No API key is ever hardcoded — the key is read
 * from the environment at build time and the fallback needs no key at all.
 */
import { frontendConfig } from '@/config/env';

/** MapTiler key, injected at build time. Empty/undefined ⇒ raster OSM fallback. */
const MAPTILER_KEY = frontendConfig.map.maptilerKey;

/** Which MapTiler vector style to request. Kept configurable to prepare for
 * style switching / dark mode without code changes (e.g. `streets-v2-dark`). */
const MAPTILER_STYLE = frontendConfig.map.maptilerStyle;

/** True when a MapTiler key is configured, i.e. vector tiles are available. */
export const hasMapTilerKey = MAPTILER_KEY.length > 0;

/** Build the MapTiler vector style URL for a given style id. */
function maptilerStyleUrl(style: string): string {
  return `https://api.maptiler.com/maps/${style}/style.json?key=${MAPTILER_KEY}`;
}

/**
 * Raster fallback tile template. Defaults to OpenStreetMap's standard endpoint
 * and can be overridden via {@link VITE_MAP_TILE_URL}. MapLibre raster sources do
 * not understand Leaflet's `{s}` subdomain token, so it is expanded to explicit
 * tile URLs below.
 */
const RASTER_TILE_URL =
  frontendConfig.map.rasterTileUrl;

/** OpenStreetMap attribution (overridable). Always shown for the raster fallback. */
export const OSM_ATTRIBUTION = frontendConfig.map.rasterAttribution;

/** Expand a `{s}` subdomain template into one URL per subdomain (a/b/c). */
function expandSubdomains(template: string): string[] {
  if (!template.includes('{s}')) return [template];
  return ['a', 'b', 'c'].map((sub) => template.replace('{s}', sub));
}

/** A self-contained MapLibre raster style built from OSM tiles (no key required). */
function rasterFallbackStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {
      'osm-raster': {
        type: 'raster',
        tiles: expandSubdomains(RASTER_TILE_URL),
        tileSize: 256,
        attribution: OSM_ATTRIBUTION,
        maxzoom: 19,
      },
    },
    layers: [{ id: 'osm-raster', type: 'raster', source: 'osm-raster' }],
  };
}

/**
 * Resolve the MapLibre `mapStyle` for the current environment.
 *
 * - With a MapTiler key → the MapTiler vector style URL (HiDPI/Retina, vector
 *   typography, dark-mode-ready). MapTiler + OSM attribution come from the style.
 * - Without a key → an OpenStreetMap raster style with explicit attribution.
 *
 * @param style Optional MapTiler style id override (prepared for style switching).
 */
export function getMapStyle(style: string = MAPTILER_STYLE): string | StyleSpecification {
  return hasMapTilerKey ? maptilerStyleUrl(style) : rasterFallbackStyle();
}

/** Fallback center used until the user picks/locates coordinates (Istanbul). */
export const DEFAULT_CENTER = { lat: 41.0082, lng: 28.9784 } as const;

export const DEFAULT_ZOOM = 13;

/**
 * Default beta fallback for the `/map` viewport (İzmir, Türkiye). Used when
 * browser geolocation is denied/unavailable so the map never opens on empty
 * ocean. We intentionally do NOT auto-search this fallback.
 */
export const DEFAULT_MAP_CENTER = { lat: 38.4237, lng: 27.1428 } as const;

export const DEFAULT_MAP_ZOOM = 12;

/** Closer zoom applied once the user's real location is found. */
export const LOCATED_ZOOM = 15;

/** Closer zoom for single-spot read-only maps. */
export const DETAIL_ZOOM = 16;

export interface LatLng {
  lat: number;
  lng: number;
}

/** True only for a finite, complete coordinate pair. */
export function isValidLatLng(lat: number | null | undefined, lng: number | null | undefined): boolean {
  return (
    typeof lat === 'number' &&
    typeof lng === 'number' &&
    Number.isFinite(lat) &&
    Number.isFinite(lng)
  );
}
