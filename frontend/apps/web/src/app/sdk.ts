import {
  type AdminApi,
  type AnalyticsApi,
  type ApiClientOptions,
  type AuthApi,
  type GamificationApi,
  type GeocodingApi,
  type MediaApi,
  type ModerationApi,
  type NotificationsApi,
  type ParkingApi,
  type PlacesApi,
  type UsersApi,
  createAdminApi,
  createAnalyticsApi,
  createApiClient,
  createAuthApi,
  createGamificationApi,
  createGeocodingApi,
  createMediaApi,
  createModerationApi,
  createNotificationsApi,
  createParkingApi,
  createPlacesApi,
  createUsersApi,
} from '@parkio/api-client';

/** Domain-only SDK surface exposed to Web features. The raw transport stays private here. */
export interface ParkioSdk {
  readonly authApi: AuthApi;
  readonly usersApi: UsersApi;
  readonly parkingApi: ParkingApi;
  readonly mediaApi: MediaApi;
  readonly notificationsApi: NotificationsApi;
  readonly gamificationApi: GamificationApi;
  readonly moderationApi: ModerationApi;
  readonly analyticsApi: AnalyticsApi;
  readonly adminApi: AdminApi;
  readonly geocodingApi: GeocodingApi;
  readonly placesApi: PlacesApi;
}

export function createParkioSdk(options: ApiClientOptions): ParkioSdk {
  const transport = createApiClient(options);

  return Object.freeze({
    authApi: createAuthApi(transport),
    usersApi: createUsersApi(transport),
    parkingApi: createParkingApi(transport),
    mediaApi: createMediaApi(transport),
    notificationsApi: createNotificationsApi(transport),
    gamificationApi: createGamificationApi(transport),
    moderationApi: createModerationApi(transport),
    analyticsApi: createAnalyticsApi(transport),
    adminApi: createAdminApi(transport),
    geocodingApi: createGeocodingApi(transport),
    placesApi: createPlacesApi(transport),
  });
}
