/**
 * Saved Places contract (WP-SPA-03).
 *
 * User-owned reusable destination shortcuts: one HOME, one WORK, many CUSTOM.
 * Coordinates/source/identity reuse Destination-compatible fields from WP-SPA-02.
 */

import type { DestinationSource, PlaceIdentity } from './destination';

export type SavedPlaceKind = 'HOME' | 'WORK' | 'CUSTOM';

/** Wire response for a saved place (`GET/PUT/POST /api/v1/places/saved…`). */
export interface SavedPlace {
  id: string;
  kind: SavedPlaceKind;
  /** Display label; HOME/WORK may be semantic defaults when no override stored. */
  label: string;
  latitude: number;
  longitude: number;
  source: DestinationSource;
  placeIdentity?: PlaceIdentity | null;
  subtitle?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SavedPlaceListResponse {
  items: SavedPlace[];
}

/** PUT home/work body — no kind, no userId. */
export interface UpsertSavedPlaceRequest {
  latitude: number;
  longitude: number;
  label?: string | null;
  source?: DestinationSource | null;
  placeIdentity?: Pick<PlaceIdentity, 'provider' | 'providerPlaceId'> | null;
  subtitle?: string | null;
}

/** POST custom / PUT by id — label required. */
export interface CreateCustomSavedPlaceRequest {
  label: string;
  latitude: number;
  longitude: number;
  source?: DestinationSource | null;
  placeIdentity?: Pick<PlaceIdentity, 'provider' | 'providerPlaceId'> | null;
  subtitle?: string | null;
}
