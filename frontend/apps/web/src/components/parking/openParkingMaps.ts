import { buildParkingMapsHttpsUrl } from './parkingLocationLinks';

/**
 * Opens the parked location in an external maps tab/window.
 * Returns false when coordinates are invalid or the browser blocked the open.
 */
export function openParkingLocationInMaps(latitude: number, longitude: number): boolean {
  let url: string;
  try {
    url = buildParkingMapsHttpsUrl(latitude, longitude);
  } catch {
    return false;
  }

  if (typeof window === 'undefined' || typeof window.open !== 'function') {
    return false;
  }

  const opened = window.open(url, '_blank', 'noopener,noreferrer');
  if (opened) {
    try {
      opened.opener = null;
    } catch {
      // Cross-origin / browser policy — noopener already applied via features.
    }
    return true;
  }
  return false;
}