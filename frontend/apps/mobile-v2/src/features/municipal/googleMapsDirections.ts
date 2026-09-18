import * as Linking from 'expo-linking';
import {
  formatParkingCoordinate,
  isValidParkingDestination,
  requireParkingDestination,
} from '@/features/parking/parkingLocationLinks';

const GOOGLE_MAPS_DIR_HOST = 'www.google.com';
const GOOGLE_MAPS_DIR_PATH = '/maps/dir/';

/**
 * Official Google Maps directions URL (no API key, no SDK).
 * Origin omitted so Google Maps can resolve the device starting point.
 * Coordinates must be validated numbers — never accept an arbitrary URL.
 */
export function buildGoogleMapsDirectionsUrl(latitude: number, longitude: number): string {
  const dest = requireParkingDestination(latitude, longitude);
  const lat = formatParkingCoordinate(dest.latitude);
  const lng = formatParkingCoordinate(dest.longitude);
  const destination = encodeURIComponent(`${lat},${lng}`);
  return `https://${GOOGLE_MAPS_DIR_HOST}${GOOGLE_MAPS_DIR_PATH}?api=1&destination=${destination}`;
}

/** True when a URL was produced by {@link buildGoogleMapsDirectionsUrl}. */
export function isTrustedGoogleMapsDirectionsUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'https:') return false;
    if (parsed.hostname !== GOOGLE_MAPS_DIR_HOST) return false;
    if (!parsed.pathname.startsWith('/maps/dir')) return false;
    if (parsed.searchParams.get('api') !== '1') return false;
    const destination = parsed.searchParams.get('destination');
    if (!destination) return false;
    const parts = destination.split(',');
    if (parts.length !== 2) return false;
    const lat = Number(parts[0]);
    const lng = Number(parts[1]);
    return isValidParkingDestination(lat, lng);
  } catch {
    return false;
  }
}

export type GoogleMapsOpenResult = 'opened' | 'invalid' | 'failed';

/**
 * Opens Google Maps directions for validated facility coordinates.
 * Reconstructs the URL internally — never opens a caller-supplied URL.
 */
export async function openGoogleMapsDirections(
  latitude: unknown,
  longitude: unknown,
): Promise<GoogleMapsOpenResult> {
  if (!isValidParkingDestination(latitude, longitude)) {
    return 'invalid';
  }
  try {
    const url = buildGoogleMapsDirectionsUrl(latitude as number, longitude as number);
    if (!isTrustedGoogleMapsDirectionsUrl(url)) {
      return 'failed';
    }
    await Linking.openURL(url);
    return 'opened';
  } catch {
    return 'failed';
  }
}
