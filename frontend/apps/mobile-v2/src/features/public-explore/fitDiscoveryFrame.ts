import type { LatLng } from '@parkio/geo';

export interface FitDiscoveryFrameCommand {
  west: number;
  south: number;
  east: number;
  north: number;
  maxZoom: number;
  padding: {
    top: number;
    bottom: number;
    left: number;
    right: number;
  };
}

const DEFAULT_PADDING = {
  top: 140,
  bottom: 120,
  left: 48,
  right: 48,
} as const;

const MAX_FIT_ZOOM = 15;

/**
 * Build a fitBounds payload for discoveryOrigin + renderable municipal pins.
 * Returns null when there are no renderable parking points (locate owns empty framing).
 */
export function buildFitDiscoveryFrame(
  anchor: LatLng,
  points: readonly LatLng[],
): FitDiscoveryFrameCommand | null {
  if (points.length === 0) return null;

  let west = anchor.lng;
  let east = anchor.lng;
  let south = anchor.lat;
  let north = anchor.lat;

  for (const point of points) {
    west = Math.min(west, point.lng);
    east = Math.max(east, point.lng);
    south = Math.min(south, point.lat);
    north = Math.max(north, point.lat);
  }

  return {
    west,
    south,
    east,
    north,
    maxZoom: MAX_FIT_ZOOM,
    padding: { ...DEFAULT_PADDING },
  };
}

/** Stable revision for framing once per discovery response. */
export function discoveryFrameRevision(
  origin: LatLng,
  facilityIds: readonly string[],
): string {
  return `${origin.lat.toFixed(6)},${origin.lng.toFixed(6)}:${facilityIds.join(',')}`;
}
