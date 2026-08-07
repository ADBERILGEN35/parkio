import { queryOptions } from '@tanstack/react-query';
import type { PlacesApi } from '@parkio/api-client';
import { placesKeys } from '../keys';

/** Headless Saved Places list (WP-SPA-07 foundation; UI in WP-SPA-08). */
export function savedPlacesQueryOptions(placesApi: PlacesApi) {
  return queryOptions({
    queryKey: placesKeys.saved(),
    queryFn: ({ signal }) => placesApi.listSavedPlaces(signal),
    staleTime: 60_000,
  });
}

export function favouriteDestinationsQueryOptions(placesApi: PlacesApi) {
  return queryOptions({
    queryKey: placesKeys.favouriteDestinations(),
    queryFn: ({ signal }) => placesApi.listFavouriteDestinations(signal),
    staleTime: 60_000,
  });
}

export function recentDestinationsQueryOptions(placesApi: PlacesApi) {
  return queryOptions({
    queryKey: placesKeys.recentDestinations(),
    queryFn: ({ signal }) => placesApi.listRecentDestinations(signal),
    staleTime: 30_000,
  });
}

export function recentParkingQueryOptions(placesApi: PlacesApi) {
  return queryOptions({
    queryKey: placesKeys.recentParking(),
    queryFn: ({ signal }) => placesApi.listRecentParking(signal),
    staleTime: 30_000,
  });
}

export function favouriteParkingQueryOptions(placesApi: PlacesApi) {
  return queryOptions({
    queryKey: placesKeys.favouriteParking(),
    queryFn: ({ signal }) => placesApi.listFavouriteParking(signal),
    staleTime: 60_000,
  });
}
