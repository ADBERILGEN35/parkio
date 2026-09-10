/**
 * Destination search suggestion contracts (WP-SPA-07).
 *
 * Client-side composition of Saved Places, favourite destinations, recent
 * destinations and geocoding results. Not a backend aggregate endpoint.
 */

import type { Destination } from './destination';
import type { SavedPlaceKind } from './saved-place';

export type DestinationSearchSource =
  | 'SAVED_PLACE'
  | 'FAVOURITE_DESTINATION'
  | 'RECENT_DESTINATION'
  | 'GEOCODING';

export type DestinationSearchGroup = DestinationSearchSource;

export interface DestinationSearchItem {
  /** Stable suggestion id for list keys (source-scoped). */
  id: string;
  source: DestinationSearchSource;
  group: DestinationSearchGroup;
  destination: Destination;
  title: string;
  subtitle?: string | null;
  savedPlaceKind?: SavedPlaceKind | null;
  /** True when the same destination is also favourited (display badge only). */
  alsoFavourite?: boolean;
  /** True when the same destination is also recent (display badge only). */
  alsoRecent?: boolean;
}

export interface DestinationSearchSection {
  group: DestinationSearchGroup;
  items: DestinationSearchItem[];
}

export interface ComposeDestinationSearchInput {
  query: string;
  savedPlaces?: Array<{
    id: string;
    kind: SavedPlaceKind;
    label: string;
    latitude: number;
    longitude: number;
    source: Destination['source'];
    placeIdentity?: Destination['placeIdentity'];
    subtitle?: string | null;
  }>;
  favouriteDestinations?: Array<{
    id: string;
    label: string;
    latitude: number;
    longitude: number;
    source: Destination['source'];
    placeIdentity?: Destination['placeIdentity'];
    subtitle?: string | null;
  }>;
  recentDestinations?: Array<{
    id: string;
    label: string;
    latitude: number;
    longitude: number;
    source: Destination['source'];
    placeIdentity?: Destination['placeIdentity'];
    subtitle?: string | null;
  }>;
  geocodingResults?: Array<{
    label: string;
    latitude: number;
    longitude: number;
    placeIdentity?: Destination['placeIdentity'];
    subtitle?: string | null;
  }>;
  /** Min chars before geocoding section is considered (default 3). */
  geocodeMinLength?: number;
  /** Max items retained per section after filter/dedupe (default 8). */
  perSectionLimit?: number;
}

export interface ComposeDestinationSearchResult {
  sections: DestinationSearchSection[];
  /** Flat ordered suggestions after cross-source dedupe. */
  items: DestinationSearchItem[];
}
