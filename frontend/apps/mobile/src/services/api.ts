import {
  AccountNotActiveError,
  createApiClient,
  createAuthApi,
  createGamificationApi,
  createGeocodingApi,
  createNotificationsApi,
  createParkingApi,
  createUsersApi,
  setRefreshHandler,
} from '@parkio/api-client';
import { appConfig } from '@/config/env';
import { useAuthStore } from '@/state/authStore';
import { secureStore } from './secureStore';
import { tokenStorage } from './tokenStorage';

/**
 * Single api-client instance for the whole app. We reuse the shared
 * `@parkio/api-client` verbatim — no API surface is re-implemented on mobile.
 * Only the platform seams are injected: the keystore-backed {@link tokenStorage}
 * and the auth-failure / suspended callbacks.
 */
export const apiClient = createApiClient({
  baseURL: appConfig.apiBaseUrl,
  tokenStorage,
  onAuthFailure: () => {
    // Refresh is unrecoverable → hard logout. Clears keystore + in-memory state.
    tokenStorage.clearTokens();
    useAuthStore.getState().clearSession();
  },
  onAccountNotActive: () => useAuthStore.getState().markSuspended(),
});

export const authApi = createAuthApi(apiClient);
export const usersApi = createUsersApi(apiClient);
export const parkingApi = createParkingApi(apiClient);
export const notificationsApi = createNotificationsApi(apiClient);
export const gamificationApi = createGamificationApi(apiClient);
export const geocodingApi = createGeocodingApi(apiClient);

/**
 * Wire the single-flight refresh implementation into the shared client.
 *
 * Concurrent 401s collapse into one `POST /auth/refresh-token` (coordinated
 * inside the api-client). The backend rotates the refresh token and presents the
 * new access token in the response body; we persist it and update the session.
 * A teardown during the in-flight refresh (logout) is detected via `sessionEpoch`
 * so a late success cannot resurrect a dead session.
 */
setRefreshHandler(async () => {
  const epochAtStart = useAuthStore.getState().sessionEpoch;
  try {
    const result = await authApi.refresh();
    if (!result.accessToken) {
      throw new Error('Refresh response did not include an access token.');
    }
    if (useAuthStore.getState().sessionEpoch !== epochAtStart) {
      return null;
    }
    tokenStorage.setTokens({ accessToken: result.accessToken });
    await secureStore.saveSession({ userId: result.user.id });
    useAuthStore.getState().setSession(result.user);
    return result.accessToken;
  } catch (error) {
    // A suspended account keeps its session so the suspended screen can show;
    // any other failure is a real auth failure.
    if (!(error instanceof AccountNotActiveError)) {
      tokenStorage.clearTokens();
      useAuthStore.getState().clearSession();
    }
    return null;
  }
});
