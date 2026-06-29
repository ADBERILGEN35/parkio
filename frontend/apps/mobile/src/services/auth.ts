import { refreshSession } from '@parkio/api-client';
import type { AuthResponse, LoginRequest, RegisterRequest } from '@parkio/types';
import { useAuthStore } from '@/state/authStore';
import { authApi } from './api';
import { secureStore } from './secureStore';
import { tokenStorage } from './tokenStorage';

/**
 * Auth operations layered on top of the api-client. Each one keeps the keystore,
 * the in-memory {@link tokenStorage} and the {@link useAuthStore} consistent so
 * there is a single, predictable session lifecycle.
 */

function applyAuthResult(result: AuthResponse) {
  if (!result.accessToken) {
    throw new Error('Authentication response did not include an access token.');
  }
  if (!result.refreshToken) {
    throw new Error('Mobile authentication response did not include a refresh token.');
  }
  tokenStorage.setTokens({ accessToken: result.accessToken, refreshToken: result.refreshToken });
  void secureStore.saveSession({
    accessToken: result.accessToken,
    refreshToken: result.refreshToken,
    userId: result.user.id,
  });
}

export async function signIn(credentials: LoginRequest): Promise<void> {
  const result = await authApi.login(credentials);
  applyAuthResult(result);
  useAuthStore.getState().setSession(result.user);
}

export async function signUp(payload: RegisterRequest): Promise<void> {
  await authApi.register(payload);
  tokenStorage.clearTokens();
  useAuthStore.getState().clearSession();
}

export async function signOut(): Promise<void> {
  const refreshToken = tokenStorage.getRefreshToken();
  try {
    await authApi.logout(refreshToken ?? undefined);
  } catch {
    // Best-effort server revocation; the local session is cleared regardless.
  } finally {
    await finishLocalLogout();
  }
}

export async function signOutAll(): Promise<void> {
  try {
    await authApi.logoutAll();
  } catch {
    // Best-effort; clear locally regardless.
  } finally {
    await finishLocalLogout();
  }
}

async function finishLocalLogout(): Promise<void> {
  tokenStorage.clearTokens();
  useAuthStore.getState().clearSession();
  await secureStore.clearSession();
}

export async function requestPasswordReset(email: string): Promise<void> {
  await authApi.forgotPassword({ email });
}

/**
 * Cold-start session restore.
 *
 * 1. Hydrate the access token from the keystore so the UI can render optimistically.
 * 2. Attempt a single-flight refresh (rotating refresh cookie / token) to obtain a
 *    fresh access token and the canonical user. On success the session is live;
 *    on failure the session is cleared. Either way bootstrap ends so route guards
 *    can stop showing the splash and decide between app and login.
 */
export async function bootstrapSession(): Promise<void> {
  const store = useAuthStore.getState();
  try {
    await tokenStorage.hydrate();
    const accessToken = await refreshSession();
    if (!accessToken) {
      await finishLocalLogout();
    }
  } catch {
    await finishLocalLogout();
  } finally {
    store.endBootstrap();
  }
}
