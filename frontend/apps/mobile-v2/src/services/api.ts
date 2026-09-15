import {
  UnauthorizedError,
  createApiClient,
  createAuthApi,
  createAnalyticsApi,
  createGamificationApi,
  createGeocodingApi,
  createMediaApi,
  createModerationApi,
  createNotificationsApi,
  createParkingApi,
  createPlacesApi,
  createPublicExploreApi,
  createPublicGeocodingApi,
  createUsersApi,
  setRefreshHandler,
} from '@parkio/api-client';
import { appConfig } from '@/config/env';
import { clearPendingAuthGateIntent } from '@/features/auth/authGateIntents';
import { useAuthStore } from '@/state/authStore';
import { configureRankingEvaluationApi } from './rankingEvaluationCorrelation';
import { getSecureStoreGeneration } from './secureStore';
import { tokenStorage } from './tokenStorage';

/**
 * Single api-client instance for the whole app — the shared `@parkio/api-client`
 * is reused verbatim; only the platform seams are injected (keystore-backed
 * token storage + auth-failure / suspended callbacks).
 */
export const apiClient = createApiClient({
  baseURL: appConfig.apiBaseUrl,
  tokenStorage,
  // Switches the backend to the native body-based refresh-token transport
  // instead of the browser HttpOnly cookie. Stamped on every request.
  defaultHeaders: { 'X-Parkio-Client': 'mobile' },
  onAuthFailure: () => {
    tokenStorage.clearTokens();
    useAuthStore.getState().clearSession();
    clearPendingAuthGateIntent();
  },
  onAccountNotActive: () => useAuthStore.getState().markSuspended(),
});

export const authApi = createAuthApi(apiClient);
export const usersApi = createUsersApi(apiClient);
export const parkingApi = createParkingApi(apiClient);
configureRankingEvaluationApi(parkingApi);
export const mediaApi = createMediaApi(apiClient);
export const notificationsApi = createNotificationsApi(apiClient);
export const gamificationApi = createGamificationApi(apiClient);
export const geocodingApi = createGeocodingApi(apiClient);
export const placesApi = createPlacesApi(apiClient);
export const moderationApi = createModerationApi(apiClient);
export const analyticsApi = createAnalyticsApi(apiClient);
/** Anonymous public Explore — no auth required; never fuse with private municipal nearby. */
export const publicExploreApi = createPublicExploreApi(apiClient);
/** Anonymous public destination search — never fuse with private /geocoding/search. */
export const publicGeocodingApi = createPublicGeocodingApi(apiClient);

/**
 * Single-flight refresh implementation. Concurrent 401s collapse into one
 * `POST /auth/refresh-token` inside the api-client. Rotated tokens are persisted
 * durably BEFORE session adoption. Logout / generation bump wins over a late
 * refresh so a dead session cannot resurrect.
 */
setRefreshHandler(async () => {
  const refreshToken = tokenStorage.getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  const epochAtStart = useAuthStore.getState().sessionEpoch;
  const generationAtStart = getSecureStoreGeneration();
  try {
    const result = await authApi.refresh(refreshToken);
    if (!result.accessToken || !result.refreshToken) {
      throw new Error('Refresh response did not include rotated tokens.');
    }
    if (useAuthStore.getState().sessionEpoch !== epochAtStart) {
      return null;
    }
    if (getSecureStoreGeneration() !== generationAtStart) {
      return null;
    }
    const persisted = await tokenStorage.persistSession({
      accessToken: result.accessToken,
      refreshToken: result.refreshToken,
      userId: result.user.id,
      expectedGeneration: generationAtStart,
    });
    if (!persisted.ok) {
      useAuthStore.getState().clearSession();
      clearPendingAuthGateIntent();
      return null;
    }
    if (useAuthStore.getState().sessionEpoch !== epochAtStart) {
      await tokenStorage.clearDurable();
      clearPendingAuthGateIntent();
      return null;
    }
    useAuthStore.getState().setSession(result.user);
    return result.accessToken;
  } catch (error) {
    // A definitive 401 means the refresh token is invalid/revoked → drop it so
    // it is never replayed and tripped as reuse. Anything else (offline, DNS,
    // 5xx) is transient: the stored token is still valid server-side and MUST
    // survive, otherwise an offline cold start permanently signs the user out.
    if (error instanceof UnauthorizedError) {
      tokenStorage.clearTokens();
      useAuthStore.getState().clearSession();
      clearPendingAuthGateIntent();
    }
    return null;
  }
});
