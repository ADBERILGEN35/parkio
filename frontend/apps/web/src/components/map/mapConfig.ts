/**
 * Map tile provider configuration.
 *
 * Defaults to OpenStreetMap, which is fine for local/dev. The provider is kept
 * configurable via env so a production tile provider can be swapped in later
 * without code changes — never hardcode a paid/production provider or API key.
 */
export const TILE_URL =
  import.meta.env.VITE_MAP_TILE_URL ?? 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

export const TILE_ATTRIBUTION =
  import.meta.env.VITE_MAP_TILE_ATTRIBUTION ??
  '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

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
