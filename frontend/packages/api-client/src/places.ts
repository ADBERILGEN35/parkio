import type {
  ConfirmRecentDestinationRequest,
  Destination,
  FavouriteDestination,
  FavouriteDestinationListResponse,
  FavouriteParking,
  FavouriteParkingListResponse,
  FavouriteParkingStatusResponse,
  CreateCustomSavedPlaceRequest,
  CreateFavouriteDestinationRequest,
  CreateFavouriteParkingRequest,
  RecentDestination,
  RecentDestinationListResponse,
  RecentParking,
  RecentParkingListResponse,
  RecordRecentParkingRequest,
  SavedPlace,
  SavedPlaceListResponse,
  UpdateFavouriteDestinationRequest,
  UpsertSavedPlaceRequest,
} from '@parkio/types';
import {
  confirmRecentDestinationRequestSchema,
  createCustomSavedPlaceRequestSchema,
  createFavouriteDestinationRequestSchema,
  createFavouriteParkingRequestSchema,
  favouriteDestinationListResponseSchema,
  favouriteDestinationSchema,
  favouriteParkingListResponseSchema,
  favouriteParkingSchema,
  favouriteParkingStatusResponseSchema,
  recentDestinationListResponseSchema,
  recentDestinationSchema,
  recentParkingListResponseSchema,
  recentParkingSchema,
  recordRecentParkingRequestSchema,
  savedPlaceListResponseSchema,
  savedPlaceSchema,
  updateFavouriteDestinationRequestSchema,
  upsertSavedPlaceRequestSchema,
} from '@parkio/validation';
import { ContractValidationError } from './errors';
import type { AxiosInstance } from 'axios';

type ContractSchema<T> = {
  safeParse(
    data: unknown,
  ): { success: true; data: T } | { success: false; error: unknown };
};

function parseContract<T>(schema: ContractSchema<T>, data: unknown): T {
  const parsed = schema.safeParse(data);
  if (!parsed.success) {
    throw new ContractValidationError();
  }
  return parsed.data;
}

function toConfirmBody(destination: Destination): ConfirmRecentDestinationRequest {
  return {
    label: destination.label,
    latitude: destination.latitude,
    longitude: destination.longitude,
    source: destination.source,
    placeIdentity: destination.placeIdentity
      ? {
          provider: destination.placeIdentity.provider,
          providerPlaceId: destination.placeIdentity.providerPlaceId,
        }
      : null,
    subtitle: destination.subtitle ?? null,
  };
}

/**
 * Places / Saved / Favourites / Recents client (WP-SPA-03/04/07).
 * Routes under `/api/v1/places/**` via gateway → user-service.
 */
export function createPlacesApi(client: AxiosInstance) {
  return {
    listSavedPlaces(signal?: AbortSignal): Promise<SavedPlace[]> {
      return client
        .get<SavedPlaceListResponse>('/places/saved', { signal })
        .then((r) => parseContract(savedPlaceListResponseSchema, r.data).items);
    },

    upsertHome(body: UpsertSavedPlaceRequest, signal?: AbortSignal): Promise<SavedPlace> {
      parseContract(upsertSavedPlaceRequestSchema, body);
      return client
        .put<SavedPlace>('/places/saved/home', body, { signal })
        .then((r) => parseContract(savedPlaceSchema, r.data));
    },

    upsertWork(body: UpsertSavedPlaceRequest, signal?: AbortSignal): Promise<SavedPlace> {
      parseContract(upsertSavedPlaceRequestSchema, body);
      return client
        .put<SavedPlace>('/places/saved/work', body, { signal })
        .then((r) => parseContract(savedPlaceSchema, r.data));
    },

    createCustomSavedPlace(
      body: CreateCustomSavedPlaceRequest,
      signal?: AbortSignal,
    ): Promise<SavedPlace> {
      parseContract(createCustomSavedPlaceRequestSchema, body);
      return client
        .post<SavedPlace>('/places/saved', body, { signal })
        .then((r) => parseContract(savedPlaceSchema, r.data));
    },

    listFavouriteParking(signal?: AbortSignal): Promise<FavouriteParking[]> {
      return client
        .get<FavouriteParkingListResponse>('/places/favourites/parking', { signal })
        .then((r) => parseContract(favouriteParkingListResponseSchema, r.data).items);
    },

    addFavouriteParking(
      body: CreateFavouriteParkingRequest,
      signal?: AbortSignal,
    ): Promise<FavouriteParking> {
      parseContract(createFavouriteParkingRequestSchema, body);
      return client
        .post<FavouriteParking>('/places/favourites/parking', body, { signal })
        .then((r) => parseContract(favouriteParkingSchema, r.data));
    },

    favouriteParkingStatus(
      targetIds: string[],
      signal?: AbortSignal,
    ): Promise<FavouriteParkingStatusResponse> {
      return client
        .get<FavouriteParkingStatusResponse>('/places/favourites/parking/status', {
          params: { targetIds },
          signal,
        })
        .then((r) => parseContract(favouriteParkingStatusResponseSchema, r.data));
    },

    listFavouriteDestinations(signal?: AbortSignal): Promise<FavouriteDestination[]> {
      return client
        .get<FavouriteDestinationListResponse>('/places/favourites/destinations', { signal })
        .then((r) => parseContract(favouriteDestinationListResponseSchema, r.data).items);
    },

    addFavouriteDestination(
      body: CreateFavouriteDestinationRequest,
      signal?: AbortSignal,
    ): Promise<FavouriteDestination> {
      parseContract(createFavouriteDestinationRequestSchema, body);
      return client
        .post<FavouriteDestination>('/places/favourites/destinations', body, { signal })
        .then((r) => parseContract(favouriteDestinationSchema, r.data));
    },

    updateFavouriteDestination(
      id: string,
      body: UpdateFavouriteDestinationRequest,
      signal?: AbortSignal,
    ): Promise<FavouriteDestination> {
      parseContract(updateFavouriteDestinationRequestSchema, body);
      return client
        .put<FavouriteDestination>(`/places/favourites/destinations/${id}`, body, { signal })
        .then((r) => parseContract(favouriteDestinationSchema, r.data));
    },

    listRecentDestinations(signal?: AbortSignal): Promise<RecentDestination[]> {
      return client
        .get<RecentDestinationListResponse>('/places/recents/destinations', { signal })
        .then((r) => parseContract(recentDestinationListResponseSchema, r.data).items);
    },

    /**
     * Explicit destination confirmation → upsert recent destination.
     * Does not call recommendations; clients call ParkingApi.recommendParking separately.
     */
    confirmRecentDestination(
      destination: Destination,
      signal?: AbortSignal,
    ): Promise<RecentDestination> {
      const body = toConfirmBody(destination);
      parseContract(confirmRecentDestinationRequestSchema, body);
      return client
        .post<RecentDestination>('/places/recents/destinations', body, { signal })
        .then((r) => parseContract(recentDestinationSchema, r.data));
    },

    deleteRecentDestination(id: string, signal?: AbortSignal): Promise<void> {
      return client.delete(`/places/recents/destinations/${id}`, { signal }).then(() => undefined);
    },

    clearRecentDestinations(signal?: AbortSignal): Promise<void> {
      return client.delete('/places/recents/destinations', { signal }).then(() => undefined);
    },

    listRecentParking(signal?: AbortSignal): Promise<RecentParking[]> {
      return client
        .get<RecentParkingListResponse>('/places/recents/parking', { signal })
        .then((r) => parseContract(recentParkingListResponseSchema, r.data).items);
    },

    recordRecentParking(
      request: RecordRecentParkingRequest,
      signal?: AbortSignal,
    ): Promise<RecentParking> {
      parseContract(recordRecentParkingRequestSchema, request);
      return client
        .post<RecentParking>('/places/recents/parking', request, { signal })
        .then((r) => parseContract(recentParkingSchema, r.data));
    },

    deleteRecentParking(id: string, signal?: AbortSignal): Promise<void> {
      return client.delete(`/places/recents/parking/${id}`, { signal }).then(() => undefined);
    },

    clearRecentParking(signal?: AbortSignal): Promise<void> {
      return client.delete('/places/recents/parking', { signal }).then(() => undefined);
    },
  };
}

export type PlacesApi = ReturnType<typeof createPlacesApi>;
