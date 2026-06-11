import {
  createApiClient,
  createAuthApi,
  createGamificationApi,
  createMediaApi,
  createNotificationsApi,
  createParkingApi,
  createUsersApi,
  DEFAULT_API_BASE_URL,
  setRefreshHandler,
} from '@parkio/api-client';
import { useAuthStore } from '@/auth/store';
import { webTokenStorage } from '@/auth/token-storage';

const baseURL = import.meta.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL;

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

setRefreshHandler(async () => {
  const refreshToken = webTokenStorage.getRefreshToken();
  if (!refreshToken) return null;

  try {
    const result = await authApi.refresh({ refreshToken });
    useAuthStore.getState().setSession(result.accessToken, result.refreshToken, result.user);
    return result.accessToken;
  } catch {
    useAuthStore.getState().clearSession();
    return null;
  }
});
