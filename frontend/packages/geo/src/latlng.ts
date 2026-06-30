/** A WGS84 coordinate pair shared by web and mobile map surfaces. */
export interface LatLng {
  lat: number;
  lng: number;
}

/** True only for a finite, complete coordinate pair. */
export function isValidLatLng(
  lat: number | null | undefined,
  lng: number | null | undefined,
): boolean {
  return (
    typeof lat === 'number' &&
    typeof lng === 'number' &&
    Number.isFinite(lat) &&
    Number.isFinite(lng)
  );
}

/** Latitude is clamped to the Web-Mercator drawable range; longitude wraps. */
export function clampLatitude(lat: number): number {
  return Math.max(-85.05112878, Math.min(85.05112878, lat));
}
