import {
  createAnalyticsApi,
  createApiClient,
  createAuthApi,
  createGamificationApi,
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

setRefreshHandler(async () => {
  try {
    const result = await authApi.refresh();
    if (!result.accessToken) {
      throw new Error('Refresh response did not include an access token.');
    }
    useAuthStore.getState().setSession(result.accessToken, result.user);
    return result.accessToken;
  } catch {
    useAuthStore.getState().clearSession();
    return null;
  }
});
