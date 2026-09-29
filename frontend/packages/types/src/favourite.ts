/**
 * Favourites contracts (WP-SPA-04).
 *
 * Municipal facility parking favourites (reference-only) and destination
 * favourites (Destination snapshot). Separate from SavedPlace.
 */

import type { DestinationSource, PlaceIdentity } from './destination';

export type FavouriteParkingTargetKind = 'MUNICIPAL_FACILITY';

export interface FavouriteParking {
  id: string;
  targetKind: FavouriteParkingTargetKind;
  targetId: string;
  createdAt: string;
}

export interface FavouriteParkingListResponse {
  items: FavouriteParking[];
}

export interface CreateFavouriteParkingRequest {
  targetKind?: FavouriteParkingTargetKind | null;
  targetId: string;
}

export interface FavouriteParkingStatusResponse {
  favouritedTargetIds: string[];
}

export interface FavouriteDestination {
  id: string;
  label: string;
  latitude: number;
  longitude: number;
  source: DestinationSource;
  placeIdentity?: PlaceIdentity | null;
  subtitle?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FavouriteDestinationListResponse {
  items: FavouriteDestination[];
}

export interface CreateFavouriteDestinationRequest {
  label: string;
  latitude: number;
  longitude: number;
  source?: DestinationSource | null;
  placeIdentity?: Pick<PlaceIdentity, 'provider' | 'providerPlaceId'> | null;
  subtitle?: string | null;
}

export interface UpdateFavouriteDestinationRequest {
  label: string;
  subtitle?: string | null;
}
