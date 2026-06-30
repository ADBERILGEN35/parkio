import type { StyleSpecification } from 'maplibre-gl';

/**
 * Centralized map configuration.
 *
 * The map renders MapLibre GL JS. The vector basemap is served by MapTiler when
 * {@link VITE_MAPTILER_KEY} is provided; otherwise it falls back to OpenStreetMap
 * raster tiles so local/dev (and any environment without a key) keeps a working
 * map instead of a blank canvas. No API key is ever hardcoded — the key is read
 * from the environment at build time and the fallback needs no key at all.
 *
 * Coordinate types, default centers/zooms and the key-free raster style builder
 * are shared with mobile via {@link @parkio/geo}; only the web-specific MapTiler
 * env wiring lives here.
 */
import { buildRasterStyle } from '@parkio/geo';
import { frontendConfig } from '@/config/env';

export {
  type LatLng,
  isValidLatLng,
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  DEFAULT_PICKER_ZOOM,
  LOCATED_ZOOM,
  DETAIL_ZOOM,
} from '@parkio/geo';

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

/** OpenStreetMap attribution (overridable). Always shown for the raster fallback. */
export const OSM_ATTRIBUTION = frontendConfig.map.rasterAttribution;

/**
 * Resolve the MapLibre `mapStyle` for the current environment.
 *
 * - With a MapTiler key → the MapTiler vector style URL (HiDPI/Retina, vector
 *   typography, dark-mode-ready). MapTiler + OSM attribution come from the style.
 * - Without a key → the shared OpenStreetMap raster style (`@parkio/geo`) with
 *   the configured tile endpoint and explicit attribution.
 *
 * @param style Optional MapTiler style id override (prepared for style switching).
 */
export function getMapStyle(style: string = MAPTILER_STYLE): string | StyleSpecification {
  if (hasMapTilerKey) return maptilerStyleUrl(style);
  // The geo raster style is structurally a MapLibre StyleSpecification.
  return buildRasterStyle({
    tileUrl: frontendConfig.map.rasterTileUrl,
    attribution: OSM_ATTRIBUTION,
  }) as unknown as StyleSpecification;
}
