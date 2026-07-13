import {
  AccountNotActiveError,
  createAnalyticsApi,
  createApiClient,
  createAuthApi,
  createGamificationApi,
  createGeocodingApi,
  createMediaApi,
  createModerationApi,
  createNotificationsApi,
  createParkingApi,
  createUsersApi,
  setRefreshHandler,
} from '@parkio/api-client';
import { useAuthStore } from '@/auth/store';
import { webTokenStorage } from '@/auth/token-storage';
import { frontendConfig } from '@/config/env';

const baseURL = frontendConfig.apiBaseUrl;

export const apiClient = createApiClient({
  baseURL,
  tokenStorage: webTokenStorage,
  onAuthFailure: () => useAuthStore.getState().clearSession(),
  onAccountNotActive: () => useAuthStore.getState().markSuspended(),
});

export const authApi = createAuthApi(apiClient);
export const usersApi = createUsersApi(apiClient);
export const parkingApi = createParkingApi(apiClient);
export const mediaApi = createMediaApi(apiClient);
export const notificationsApi = createNotificationsApi(apiClient);
export const gamificationApi = createGamificationApi(apiClient);
export const moderationApi = createModerationApi(apiClient);
export const analyticsApi = createAnalyticsApi(apiClient);
export const geocodingApi = createGeocodingApi(apiClient);

// Single source of truth for the refresh network call and the resulting session
// mutation. The shared single-flight coordinator (refreshSession) guarantees this
// runs at most once per in-flight refresh, so the session is set or cleared
// exactly once even when many callers (bootstrap, 401 retries) await together.
setRefreshHandler(async () => {
  // Capture the session generation before the network call so a logout that
  // happens while this refresh is in flight cannot be overwritten by a late
  // success below.
  const epochAtStart = useAuthStore.getState().sessionEpoch;
  try {
    const result = await authApi.refresh();
    if (!result.accessToken) {
      throw new Error('Refresh response did not include an access token.');
    }
    if (useAuthStore.getState().sessionEpoch !== epochAtStart) {
      // The session was torn down (logout/clear) during refresh — do not
      // resurrect it, and treat the refresh as unusable for any pending retry.
      return null;
    }
    useAuthStore.getState().setSession(result.accessToken, result.user);
    return result.accessToken;
  } catch (error) {
    // A 403 ACCOUNT_NOT_ACTIVE means the refresh token is valid but the account
    // is suspended; the response interceptor already flipped the suspended flag
    // via onAccountNotActive. Keep the session so the suspended screen (with
    // logout) shows instead of bouncing to /login.
    if (!(error instanceof AccountNotActiveError)) {
      useAuthStore.getState().clearSession();
    }
    return null;
  }
});
