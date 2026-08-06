import { queryOptions } from '@tanstack/react-query';
import { placesApi } from '@/services/api';
import { placesKeys } from '../keys';

export function savedPlacesQueryOptions() {
  return queryOptions({
    queryKey: placesKeys.saved(),
    queryFn: ({ signal }) => placesApi.listSavedPlaces(signal),
    staleTime: 60_000,
  });
}

export function favouriteDestinationsQueryOptions() {
  return queryOptions({
    queryKey: placesKeys.favouriteDestinations(),
    queryFn: ({ signal }) => placesApi.listFavouriteDestinations(signal),
    staleTime: 60_000,
  });
}

export function recentDestinationsQueryOptions() {
  return queryOptions({
    queryKey: placesKeys.recentDestinations(),
    queryFn: ({ signal }) => placesApi.listRecentDestinations(signal),
    staleTime: 30_000,
  });
}

export function recentParkingQueryOptions() {
  return queryOptions({
    queryKey: placesKeys.recentParking(),
    queryFn: ({ signal }) => placesApi.listRecentParking(signal),
    staleTime: 30_000,
  });
}
