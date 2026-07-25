import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';

/**
 * Pure Parking Session maps/share URL builders for web (PR4).
 * Adapted from mobile-v2 parkingLocationLinks — HTTPS-only for browsers.
 * Coordinates stay in-memory; never embed session IDs or user identity.
 */

export type ParkingShareContent = {
  title: string;
  text: string;
  url: string;
};

/** Locale-independent decimal formatting (never toLocaleString). */
export function formatParkingCoordinate(value: number): string {
  if (!Number.isFinite(value)) {
    throw new Error('invalid_destination');
  }
  return value.toFixed(7).replace(/\.?0+$/, '');
}

/**
 * HTTPS OpenStreetMap pin URL suitable for window.open and share/clipboard.
 * Reuses the PR2 parked-coordinate validator — does not invent a second ruleset.
 */
export function buildParkingMapsHttpsUrl(latitude: number, longitude: number): string {
  if (!isUsableParkedCoordinate(latitude, longitude)) {
    throw new Error('invalid_destination');
  }
  const lat = formatParkingCoordinate(latitude);
  const lng = formatParkingCoordinate(longitude);
  return `https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=18/${lat}/${lng}`;
}

/**
 * Localized share payload + maps HTTPS link. Callers pass already-translated
 * title/lead; this helper never adds session metadata or raw coord copy beyond the URL.
 */
export function buildParkingShareContent(
  latitude: number,
  longitude: number,
  localizedTitle: string,
  localizedLead: string,
): ParkingShareContent {
  const url = buildParkingMapsHttpsUrl(latitude, longitude);
  const lead = localizedLead.trim();
  const text = lead.length > 0 ? `${lead}\n${url}` : url;
  return { title: localizedTitle.trim(), text, url };
}