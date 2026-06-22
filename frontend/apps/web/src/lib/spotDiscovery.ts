import type { ParkingStatus, PublicSpot } from '@parkio/types';
import type { LatLng } from '@/components/map/mapConfig';

/**
 * Pure, backend-faithful discovery helpers for the map results experience.
 *
 * IMPORTANT — no fabricated data. Every value here is derived only from fields
 * the parking-service already returns on `PublicSpot` (coordinates, timestamps,
 * status, legalStatus). Distance is computed client-side from the *real* search
 * center and the spot's real coordinates; there is no ETA, popularity, or
 * confidence inference. Sorting and filtering operate on these real fields only.
 */

/** A spot enriched with straight-line distance from the active search center. */
export interface SpotWithDistance extends PublicSpot {
  /** Meters from the search center, or `null` when no center is known. */
  distanceMeters: number | null;
}

/** Sort modes — each maps to a single real field (no synthetic ranking). */
export type SpotSort = 'nearest' | 'newest' | 'recent';

export const SPOT_SORT_LABELS: Record<SpotSort, string> = {
  nearest: 'Nearest',
  newest: 'Newest',
  recent: 'Recently updated',
};

/**
 * Client-side presentation filters.
 *
 * The `GET /parking/spots/nearby` endpoint only accepts lat/lng/radius/limit —
 * it has no server-side faceting. These filters therefore narrow the already
 * fetched result set in the browser only; they never hit the backend.
 */
export interface SpotFilters {
  /** Selected statuses; an empty array means "all statuses". */
  statuses: ParkingStatus[];
  /** When true, keep only `legalStatus === 'LEGAL'` spots. */
  legalOnly: boolean;
}

export const EMPTY_FILTERS: SpotFilters = { statuses: [], legalOnly: false };

/** True when any presentation filter is narrowing the list. */
export function hasActiveFilters(filters: SpotFilters): boolean {
  return filters.statuses.length > 0 || filters.legalOnly;
}

const EARTH_RADIUS_M = 6_371_000;
const toRad = (deg: number): number => (deg * Math.PI) / 180;

/** Great-circle distance between two coordinates, in meters (haversine). */
export function haversineMeters(a: LatLng, b: LatLng): number {
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Human distance: `120 m`, `1.4 km`, `12 km`. */
export function formatDistance(meters: number): string {
  if (meters < 1000) return `${Math.round(meters)} m`;
  const km = meters / 1000;
  return `${km < 10 ? km.toFixed(1) : Math.round(km)} km`;
}

/** Attach a real-coordinate distance to each spot (null when no center). */
export function withDistance(spots: PublicSpot[], center: LatLng | null): SpotWithDistance[] {
  return spots.map((spot) => ({
    ...spot,
    distanceMeters: center
      ? haversineMeters(center, { lat: spot.latitude, lng: spot.longitude })
      : null,
  }));
}

/** Apply presentation filters; returns a new array. */
export function filterSpots<T extends PublicSpot>(spots: T[], filters: SpotFilters): T[] {
  if (!hasActiveFilters(filters)) return spots;
  return spots.filter((spot) => {
    if (filters.statuses.length > 0 && !filters.statuses.includes(spot.status)) return false;
    if (filters.legalOnly && spot.legalStatus !== 'LEGAL') return false;
    return true;
  });
}

const byTimeDesc = (a: string, b: string): number =>
  new Date(b).getTime() - new Date(a).getTime();

/** Sort by the chosen real field; returns a new array (stable for ties). */
export function sortSpots(spots: SpotWithDistance[], sort: SpotSort): SpotWithDistance[] {
  const copy = [...spots];
  switch (sort) {
    case 'nearest':
      // Spots without a distance (no center) sink to the bottom.
      return copy.sort((a, b) => {
        if (a.distanceMeters === null) return b.distanceMeters === null ? 0 : 1;
        if (b.distanceMeters === null) return -1;
        return a.distanceMeters - b.distanceMeters;
      });
    case 'newest':
      return copy.sort((a, b) => byTimeDesc(a.createdAt, b.createdAt));
    case 'recent':
      return copy.sort((a, b) => byTimeDesc(a.updatedAt, b.updatedAt));
    default:
      return copy;
  }
}

/** Sort modes offered for the current center availability. */
export function availableSorts(hasCenter: boolean): SpotSort[] {
  return hasCenter ? ['nearest', 'newest', 'recent'] : ['newest', 'recent'];
}

/** Sensible default sort: nearest when a center exists, else newest. */
export function defaultSort(hasCenter: boolean): SpotSort {
  return hasCenter ? 'nearest' : 'newest';
}

/** Distinct statuses present in a result set, in canonical status order. */
const STATUS_ORDER: ParkingStatus[] = [
  'ACTIVE',
  'VERIFIED',
  'SUSPICIOUS',
  'FILLED',
  'EXPIRED',
  'REJECTED',
];

export function availableStatuses(spots: PublicSpot[]): ParkingStatus[] {
  const present = new Set(spots.map((spot) => spot.status));
  return STATUS_ORDER.filter((status) => present.has(status));
}
