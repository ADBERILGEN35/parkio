import { authApi } from '@/api';
import { useAuthStore } from './store';
import { webTokenStorage } from './token-storage';

/**
 * Revokes the refresh token server-side when one exists, then always clears
 * local auth state. Route guards redirect to /login once the session is gone.
 */
export async function performLogout(): Promise<void> {
  const refreshToken = webTokenStorage.getRefreshToken();
  if (refreshToken) {
    try {
      await authApi.logout({ refreshToken });
    } catch {
      // Local state is cleared regardless of backend logout outcome.
    }
  }
  useAuthStore.getState().clearSession();
}
