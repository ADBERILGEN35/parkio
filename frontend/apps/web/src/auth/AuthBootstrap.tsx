import { AccountNotActiveError } from '@parkio/api-client';
import { useEffect } from 'react';
import { authApi } from '@/api';
import { useAuthStore } from './store';

/** Restores a session after reload by using the HttpOnly refresh cookie. */
export function AuthBootstrap() {
  const accessToken = useAuthStore((s) => s.accessToken);
  const user = useAuthStore((s) => s.user);
  const setSession = useAuthStore((s) => s.setSession);
  const clearSession = useAuthStore((s) => s.clearSession);

  useEffect(() => {
    if (accessToken || user) return;

    authApi
      .refresh()
      .then((result) => {
        if (!result.accessToken) {
          throw new Error('Refresh response did not include an access token.');
        }
        setSession(result.accessToken, result.user);
      })
      .catch((error: unknown) => {
        // The api-client already marked the store suspended; keep the session
        // so the suspended screen (with logout) is shown instead of /login.
        if (error instanceof AccountNotActiveError) return;

        clearSession();
      });
  }, [accessToken, user, setSession, clearSession]);

  return null;
}
