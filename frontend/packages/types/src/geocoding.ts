/**
 * Forward-geocoding (place text → coordinates) wire types — mirrors
 * parking-service's `GeocodeResultResponse` / `GeocodeSearchResponse`.
 *
 * Geocoding is proxied through the gateway (`GET /api/v1/geocoding/search`), not
 * called from the browser (ADR-014); the SPA only sees these shapes.
 */

/** A single geocoding candidate. */
export interface GeocodeResult {
  /** Stable provider id, used for list keys. */
  id: string;
  /** Full human-readable address. */
  displayName: string;
  /** Short primary label (place/street name or first address segment). */
  primary: string;
  /** Secondary label, e.g. "Konak, İzmir"; empty when unavailable. */
  secondary: string;
  lat: number;
  lng: number;
}

/** Envelope returned by `GET /api/v1/geocoding/search`. */
export interface GeocodeSearchResponse {
  results: GeocodeResult[];
}
