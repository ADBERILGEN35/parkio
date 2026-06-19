/**
 * Forward geocoding (address/place text → coordinates) for the `/map` search.
 *
 * Uses OpenStreetMap **Nominatim** directly from the browser for the local beta.
 * This is intentionally NOT routed through the parking gateway/backend — it is a
 * third-party lookup that turns user text into lat/lng, which then feeds the
 * existing `GET /parking/spots/nearby` call.
 *
 * Production note: Nominatim's public endpoint has a strict usage policy and no
 * SLA. Before production, move geocoding behind the backend or a provider with
 * an SLA/key and point `VITE_GEOCODING_BASE_URL` at it. No API key is stored
 * here and no paid provider is hardcoded.
 */
import { frontendConfig } from '@/config/env';

/** Base URL without a trailing slash so `${base}/search` is always well-formed. */
export const GEOCODING_BASE_URL = frontendConfig.geocoding.baseUrl;

/** Max results requested from / surfaced by the geocoder. */
export const GEOCODING_RESULT_LIMIT = 5;

export interface GeocodeResult {
  /** Stable id (Nominatim `place_id`) for list keys. */
  id: string;
  /** Full human-readable address from the provider. */
  displayName: string;
  /** Short primary label (street/place name or first address segment). */
  primary: string;
  /** Secondary label, e.g. "Konak, İzmir" — empty when unavailable. */
  secondary: string;
  lat: number;
  lng: number;
}

interface NominatimAddress {
  road?: string;
  pedestrian?: string;
  neighbourhood?: string;
  suburb?: string;
  quarter?: string;
  city_district?: string;
  district?: string;
  county?: string;
  town?: string;
  city?: string;
  village?: string;
  province?: string;
  state?: string;
}

interface NominatimItem {
  place_id?: number | string;
  display_name?: string;
  name?: string;
  lat?: string;
  lon?: string;
  address?: NominatimAddress;
}

function toResult(item: NominatimItem): GeocodeResult | null {
  const lat = Number(item.lat);
  const lng = Number(item.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;

  const address = item.address ?? {};
  const city = address.city ?? address.town ?? address.village ?? address.province ?? address.state;
  const district =
    address.city_district ?? address.district ?? address.county ?? address.suburb ?? address.quarter;

  // De-duplicate (district === city) and drop blanks.
  const secondary = [...new Set([district, city].filter(Boolean) as string[])].join(', ');

  const displayName = item.display_name?.trim() ?? '';
  const primary =
    item.name?.trim() ||
    address.road ||
    address.pedestrian ||
    displayName.split(',')[0]?.trim() ||
    displayName;

  return {
    id: String(item.place_id ?? `${lat},${lng}`),
    displayName: displayName || primary,
    primary: primary || displayName,
    secondary,
    lat,
    lng,
  };
}

/**
 * Geocode free-text into up to {@link GEOCODING_RESULT_LIMIT} candidate places.
 * Biased to Turkey (`countrycodes=tr`, `accept-language=tr`). Throws on network
 * or non-2xx responses so callers can show a friendly error without breaking the
 * separate parking search.
 */
export async function geocodePlaces(query: string, signal?: AbortSignal): Promise<GeocodeResult[]> {
  const trimmed = query.trim();
  if (!trimmed) return [];

  const url = new URL(`${GEOCODING_BASE_URL}/search`);
  url.searchParams.set('q', trimmed);
  url.searchParams.set('format', 'jsonv2');
  url.searchParams.set('addressdetails', '1');
  url.searchParams.set('countrycodes', 'tr');
  url.searchParams.set('accept-language', 'tr');
  url.searchParams.set('limit', String(GEOCODING_RESULT_LIMIT));

  const response = await fetch(url.toString(), { headers: { Accept: 'application/json' }, signal });
  if (!response.ok) {
    throw new Error(`Geocoding request failed with status ${response.status}`);
  }

  const data = (await response.json()) as NominatimItem[];
  if (!Array.isArray(data)) return [];

  return data
    .map(toResult)
    .filter((result): result is GeocodeResult => result !== null)
    .slice(0, GEOCODING_RESULT_LIMIT);
}
