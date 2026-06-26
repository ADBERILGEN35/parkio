/**
 * Forward geocoding (address/place text → coordinates) for the `/map` search.
 *
 * Routed through the authenticated backend (`GET /api/v1/geocoding/search` via the
 * gateway → parking-service → provider), NOT called from the browser (ADR-014).
 * The provider, its key/host and usage-policy handling live server-side; this
 * module is a thin pass-through that preserves the existing `GeocodeResult`
 * contract so callers (`usePlaceAutocomplete`, MapPage, UploadPage) are unchanged.
 */
import { geocodingApi } from '@/api';
import type { GeocodeResult } from '@parkio/types';

export type { GeocodeResult } from '@parkio/types';

/** Max results requested from / surfaced by the geocoder. */
export const GEOCODING_RESULT_LIMIT = 5;

/**
 * Geocode free-text into up to {@link GEOCODING_RESULT_LIMIT} candidate places.
 * Country/language bias now lives server-side. Throws on network or non-2xx
 * responses (the shared api client maps these to a {@code ParkioApiError}) so
 * callers can show a friendly error without breaking the separate parking search.
 */
export async function geocodePlaces(query: string, signal?: AbortSignal): Promise<GeocodeResult[]> {
  const trimmed = query.trim();
  if (!trimmed) return [];
  return geocodingApi.searchPlaces(trimmed, GEOCODING_RESULT_LIMIT, signal);
}
