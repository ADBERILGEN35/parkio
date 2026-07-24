import { useEffect } from 'react';
import { useAppRuntime } from '@/app/AppRuntimeContext';
import { findRouteManifestEntryByPath } from '@/routing/route-manifest';

/**
 * Starts the runtime-scoped bootstrap policy selected by canonical route metadata.
 * Public entry settles immediately; protected entry restores through the SDK-owned
 * refresh coordinator.
 */
export function AuthBootstrap() {
  const { authSession, router } = useAppRuntime();
  const route = findRouteManifestEntryByPath(
    router.state.location.pathname,
  );
  const entry = route?.bootstrap === 'protected-await' ? 'protected' : 'public';

  useEffect(() => {
    void authSession.bootstrap(entry);
  }, [authSession, entry]);

  return null;
}
