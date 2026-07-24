import { useEffect } from 'react';
import { useAppRuntime } from '@/app/AppRuntimeContext';
import { clearUserSessionQueries } from './sessionQueryCache';

/**
 * Bridges auth identity transitions to the Query cache (WP-04).
 * Route selection remains owned by WP-03 / AuthBootstrap; this component only
 * clears user-scoped server state when the authenticated identity changes.
 */
export function SessionQueryCacheSync() {
  const { queryClient, authStore } = useAppRuntime();

  useEffect(() => {
    return authStore.subscribeIdentityChanges(({ previous, current }) => {
      if (previous.userId === current.userId) {
        return;
      }
      clearUserSessionQueries(queryClient);
    });
  }, [authStore, queryClient]);

  return null;
}
