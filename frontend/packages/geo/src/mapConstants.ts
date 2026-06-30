import type { LatLng } from './latlng';

/**
 * Single product fallback center (İzmir, Türkiye) — the current hosted-beta
 * geography. Every map surface starts here when device geolocation is
 * denied/unavailable. We intentionally do NOT auto-search this fallback.
 */
export const DEFAULT_MAP_CENTER: LatLng = { lat: 38.4237, lng: 27.1428 };

/** City-overview zoom used as the initial map zoom. */
export const DEFAULT_MAP_ZOOM = 12;

/** Slightly closer zoom for click-to-place pickers. */
export const DEFAULT_PICKER_ZOOM = 13;

/** Closer zoom applied once the user's real location is found. */
export const LOCATED_ZOOM = 15;

/** Closer zoom for single-spot read-only maps. */
export const DETAIL_ZOOM = 16;

/** Default nearby-search radius (meters) — backend caps at 50 000, default 1000. */
export const DEFAULT_NEARBY_RADIUS_M = 1500;

/** Max spots the nearby endpoint returns (backend hard cap). */
export const NEARBY_RESULT_LIMIT = 50;
