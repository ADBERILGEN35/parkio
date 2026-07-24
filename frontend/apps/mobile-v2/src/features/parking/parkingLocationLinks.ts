/**
 * Pure builders for ParkingSession return-navigation and share content (S1-P0-10).
 * Coordinates stay in-memory only — never persist these URLs or messages.
 */

export type ParkingCoordinates = {
  latitude: number;
  longitude: number;
};

export type ParkingNavPlatform = 'ios' | 'android' | 'default';

/** Locale-independent decimal formatting (never toLocaleString). */
export function formatParkingCoordinate(value: number): string {
  if (!Number.isFinite(value)) {
    throw new Error('invalid_destination');
  }
  // ECMAScript Number formatting always uses '.' — trim excess fixed zeros.
  return value.toFixed(7).replace(/\.?0+$/, '');
}

export function isValidParkingDestination(latitude: unknown, longitude: unknown): boolean {
  if (typeof latitude !== 'number' || typeof longitude !== 'number') {
    return false;
  }
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    return false;
  }
  if (latitude < -90 || latitude > 90) {
    return false;
  }
  if (longitude < -180 || longitude > 180) {
    return false;
  }
  return true;
}

export function requireParkingDestination(
  latitude: unknown,
  longitude: unknown,
): ParkingCoordinates {
  if (!isValidParkingDestination(latitude, longitude)) {
    throw new Error('invalid_destination');
  }
  return { latitude: latitude as number, longitude: longitude as number };
}

/** HTTPS maps link suitable for share sheets and Linking fallback. */
export function buildParkingMapsHttpsUrl(latitude: number, longitude: number): string {
  const dest = requireParkingDestination(latitude, longitude);
  const lat = formatParkingCoordinate(dest.latitude);
  const lng = formatParkingCoordinate(dest.longitude);
  return `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=18/${lat}/${lng}`;
}

/**
 * Platform-native navigation URL. iOS → Apple Maps; Android → geo intent;
 * default/fallback → HTTPS OSM (no proprietary Google-only scheme required).
 */
export function buildParkingNavigationUrl(
  latitude: number,
  longitude: number,
  platform: ParkingNavPlatform,
  label = 'Parking',
): string {
  const dest = requireParkingDestination(latitude, longitude);
  const lat = formatParkingCoordinate(dest.latitude);
  const lng = formatParkingCoordinate(dest.longitude);
  const encodedLabel = encodeURIComponent(label);

  switch (platform) {
    case 'ios':
      return `maps://?daddr=${lat},${lng}&q=${encodedLabel}`;
    case 'android':
      return `geo:${lat},${lng}?q=${lat},${lng}(${encodedLabel})`;
    default:
      return buildParkingMapsHttpsUrl(dest.latitude, dest.longitude);
  }
}

export type ParkingShareContent = {
  message: string;
  url: string;
};

/**
 * Localized share body + maps HTTPS link. Callers must pass already-localized
 * short text; this helper only concatenates without adding session metadata.
 */
export function buildParkingShareContent(
  latitude: number,
  longitude: number,
  localizedLead: string,
): ParkingShareContent {
  const url = buildParkingMapsHttpsUrl(latitude, longitude);
  const lead = localizedLead.trim();
  const message = lead.length > 0 ? `${lead}\n${url}` : url;
  return { message, url };
}