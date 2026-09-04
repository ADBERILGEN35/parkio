/**
 * Destination domain contract for Smart Parking Experience (WP-SPA-02+).
 *
 * Provider-neutral place intent — not a geocoding wire twin and not a parking
 * facility. Optional placeIdentity is absent when only coordinates+label exist.
 *
 * WP-SPA-02 does not expose a public Destination CRUD endpoint; these types are
 * the shared frontend contract for later packages (saved places, recommendations).
 */

export type DestinationSource = 'GEOCODING' | 'MAP_PIN' | 'SYSTEM';

/** Namespaced provider identity; never invent from labels. */
export interface PlaceIdentity {
  /** Lowercase kebab-case provider namespace, e.g. `osm-nominatim`. */
  provider: string;
  /** Provider-local place id (not a Parkio UUID). */
  providerPlaceId: string;
  /** Deterministic `provider:providerPlaceId`. */
  canonicalKey: string;
}

/** Canonical trip-target place. */
export interface Destination {
  label: string;
  latitude: number;
  longitude: number;
  source: DestinationSource;
  /** Present only when a stable provider id exists. */
  placeIdentity?: PlaceIdentity | null;
  /** Secondary display line (district/city); omitted when empty. */
  subtitle?: string | null;
}
