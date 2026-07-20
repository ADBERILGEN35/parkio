import { refreshSession } from '@parkio/api-client';
import type { AuthResponse } from '@parkio/types';
import { useAuthStore } from '@/state/authStore';
import { authApi } from './api';
import { secureStore } from './secureStore';
import { tokenStorage } from './tokenStorage';

/**
 * Session lifecycle orchestration: cold-start restore, sign-in/out.
 * Components never touch tokenStorage directly — they call these.
 */

/**
 * Cold-start session restore. Hydrates the keystore tokens into memory, then
 * rotates the refresh token via the shared single-flight coordinator (which
 * also populates the auth store on success). Always resolves; the auth store
 * ends in `authenticated` or `anonymous` (or `suspended` via the 403 callback).
 */
export async function bootstrapSession(): Promise<void> {
  const store = useAuthStore.getState();
  try {
    await tokenStorage.hydrate();
    if (!tokenStorage.getRefreshToken()) {
      return;
    }
    await refreshSession();
  } catch (error) {
    console.warn('[auth] bootstrap failed', error);
  } finally {
    useAuthStore.getState().finishBootstrap();
    void store;
  }
}

/** Persist a login/register response that carries tokens (login always does). */
export function adoptSession(response: AuthResponse): void {
  if (response.accessToken) {
    tokenStorage.setTokens({
      accessToken: response.accessToken,
      ...(response.refreshToken ? { refreshToken: response.refreshToken } : {}),
    });
  }
  void secureStore.saveSession({ userId: response.user.id });
  useAuthStore.getState().setSession(response.user);
}

/**
 * Sign out. Best-effort server-side revocation (the local teardown must never
 * be blocked by a network failure), then local state is cleared.
 */
export async function signOut(options?: { allDevices?: boolean }): Promise<void> {
  const refreshToken = tokenStorage.getRefreshToken();
  try {
    if (options?.allDevices) {
      await authApi.logoutAll();
    } else if (refreshToken) {
      await authApi.logout(refreshToken);
    }
  } catch (error) {
    console.warn('[auth] server-side logout failed (continuing local sign-out)', error);
  } finally {
    tokenStorage.clearTokens();
    useAuthStore.getState().clearSession();
  }
}
