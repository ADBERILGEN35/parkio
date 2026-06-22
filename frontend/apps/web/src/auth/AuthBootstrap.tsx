import { refreshSession } from '@parkio/api-client';
import { useEffect } from 'react';
import { initCrossTabAuth } from './crossTabSync';
import { useAuthStore } from './store';

/**
 * Restores a session after reload using the HttpOnly refresh cookie, through the
 * shared single-flight refresh coordinator.
 *
 * Under React StrictMode (dev) this effect is invoked twice, and the same race
 * can happen in production via two tabs, a bootstrap racing an API 401, network
 * retries, or concurrent route loaders. The coordinator collapses all of those
 * into a single POST /auth/refresh-token, so the rotated refresh cookie is never
 * replayed — which the backend would otherwise treat as token reuse and revoke
 * the whole token family. Session updates (setSession / clearSession) are owned
 * by the refresh handler in `@/api`, so a duplicate invocation here cannot clear
 * a session that the shared refresh just established.
 */
export function AuthBootstrap() {
  const hasSession = useAuthStore((s) => Boolean(s.accessToken || s.user));
  const endBootstrap = useAuthStore((s) => s.endBootstrap);

  // When another tab signs out, drop this tab's in-memory session too.
  useEffect(() => initCrossTabAuth(() => useAuthStore.getState().clearSession()), []);

  useEffect(() => {
    if (hasSession) {
      endBootstrap();
      return;
    }

    let active = true;
    void refreshSession().finally(() => {
      // Mark bootstrap done so guards stop showing the loader and can decide
      // between the restored session and /login. The handler already applied
      // setSession/clearSession; we only flip the "still deciding" flag.
      if (active) endBootstrap();
    });

    return () => {
      active = false;
    };
  }, [hasSession, endBootstrap]);

  return null;
}
