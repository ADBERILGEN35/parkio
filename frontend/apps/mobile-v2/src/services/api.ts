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
  createUsersApi,
  setRefreshHandler,
} from '@parkio/api-client';
import { appConfig } from '@/config/env';
import { useAuthStore } from '@/state/authStore';
import { configureRankingEvaluationApi } from './rankingEvaluationCorrelation';
import { secureStore } from './secureStore';
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

/**
 * Single-flight refresh implementation. Concurrent 401s collapse into one
 * `POST /auth/refresh-token` inside the api-client. The keystore refresh token
 * is replayed in the body; the backend rotates it and returns fresh access AND
 * refresh tokens, both persisted. `sessionEpoch` detects a logout racing the
 * in-flight refresh so a late success cannot resurrect a dead session.
 */
setRefreshHandler(async () => {
  const refreshToken = tokenStorage.getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  const epochAtStart = useAuthStore.getState().sessionEpoch;
  try {
    const result = await authApi.refresh(refreshToken);
    if (!result.accessToken || !result.refreshToken) {
      throw new Error('Refresh response did not include rotated tokens.');
    }
    if (useAuthStore.getState().sessionEpoch !== epochAtStart) {
      return null;
    }
    tokenStorage.setTokens({ accessToken: result.accessToken, refreshToken: result.refreshToken });
    await secureStore.saveSession({ userId: result.user.id });
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
    }
    return null;
  }
});
