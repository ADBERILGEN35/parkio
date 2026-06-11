import { AccountNotActiveError, UnauthorizedError } from '@parkio/api-client';
import { useEffect } from 'react';
import { authApi } from '@/api';
import { useAuthStore } from './store';

/** Restores the user profile when a persisted access token exists (page refresh). */
export function AuthBootstrap() {
  const accessToken = useAuthStore((s) => s.accessToken);
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);
  const clearSession = useAuthStore((s) => s.clearSession);

  useEffect(() => {
    if (!accessToken || user) return;

    authApi
      .me()
      .then(setUser)
      .catch((error: unknown) => {
        // The api-client already marked the store suspended; keep the session
        // so the suspended screen (with logout) is shown instead of /login.
        if (error instanceof AccountNotActiveError) return;

        // A surfaced 401 means the silent refresh already ran and failed
        // (or no refresh token exists) — hard logout. Transient failures
        // (network, 5xx) keep the stored tokens for the next attempt.
        if (error instanceof UnauthorizedError) clearSession();
      });
  }, [accessToken, user, setUser, clearSession]);

  return null;
}
