import { refreshSession } from '@parkio/api-client';
import type { AuthResponse } from '@parkio/types';
import { runRegisteredSessionQueryCleanup } from '@/data/sessionQueryCleanup';
import { clearPendingAuthGateIntent } from '@/features/auth/authGateIntents';
import { useAuthStore } from '@/state/authStore';
import { authApi } from './api';
import { PersistenceError, getSecureStoreGeneration } from './secureStore';
import { tokenStorage } from './tokenStorage';

/**
 * Session lifecycle orchestration: cold-start restore, sign-in/out.
 * Components never touch tokenStorage directly — they call these.
 */

export class SessionPersistenceFailure extends Error {
  readonly causeCode: PersistenceError['code'];

  constructor(cause: PersistenceError) {
    super('session_persistence_failed');
    this.name = 'SessionPersistenceFailure';
    this.causeCode = cause.code;
  }
}

/**
 * Cold-start session restore. Hydrates the keystore tokens into memory, then
 * rotates the refresh token via the shared single-flight coordinator.
 */
export async function bootstrapSession(): Promise<void> {
  try {
    await tokenStorage.hydrate();
    if (!tokenStorage.getRefreshToken()) {
      return;
    }
    await refreshSession();
  } catch (error) {
    console.warn('[auth] bootstrap failed', error instanceof Error ? error.name : 'error');
  } finally {
    useAuthStore.getState().finishBootstrap();
  }
}

/**
 * Persist a login/register response durably, then adopt the authenticated session.
 * If SecureStore write fails: no durable login, memory cleared, safe error thrown.
 */
export async function adoptSession(response: AuthResponse): Promise<void> {
  if (!response.accessToken || !response.refreshToken) {
    throw new SessionPersistenceFailure(
      new PersistenceError('corrupt_record', 'login_tokens_missing'),
    );
  }
  const expectedGeneration = getSecureStoreGeneration();
  const persisted = await tokenStorage.persistSession({
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    userId: response.user.id,
    expectedGeneration,
  });
  if (!persisted.ok) {
    useAuthStore.getState().clearSession();
    clearPendingAuthGateIntent();
    throw new SessionPersistenceFailure(persisted.error);
  }
  useAuthStore.getState().setSession(response.user);
}

/**
 * Terminal local logout wins over in-flight refresh/save.
 *
 * Order:
 * 1. invalidate persistence generation + clear in-memory tokens
 * 2. cancel + remove SESSION_PRIVATE queries
 * 3. clear AuthGate intents + UI session (anonymous)
 * 4. best-effort server revoke
 * 5. await durable SecureStore clear
 */
export async function signOut(options?: { allDevices?: boolean }): Promise<void> {
  const refreshToken = tokenStorage.getRefreshToken();

  const durable = tokenStorage.clearDurable();
  await runRegisteredSessionQueryCleanup();
  useAuthStore.getState().clearSession();
  clearPendingAuthGateIntent();

  try {
    if (options?.allDevices) {
      await authApi.logoutAll();
    } else if (refreshToken) {
      await authApi.logout(refreshToken);
    }
  } catch (error) {
    console.warn(
      '[auth] server-side logout failed (local sign-out already applied)',
      error instanceof Error ? error.name : 'error',
    );
  }

  const result = await durable;
  if (!result.ok) {
    console.warn('[auth] durable clear reported', result.error.code);
  }
}
