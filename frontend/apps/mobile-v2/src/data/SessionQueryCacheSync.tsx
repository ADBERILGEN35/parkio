import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '@/state/authStore';
import { clearUserSessionQueries } from './sessionQueryCache';

/**
 * Bridges auth identity transitions to the Query cache (WP-07).
 * Navigation/redirect ownership stays in Expo Router layouts; this only
 * clears user-scoped server state when the authenticated identity changes.
 */
export function SessionQueryCacheSync() {
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const previousUserId = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    if (previousUserId.current === undefined) {
      previousUserId.current = userId;
      return;
    }
    if (previousUserId.current !== userId) {
      clearUserSessionQueries(queryClient);
      previousUserId.current = userId;
    }
  }, [userId, queryClient]);

  return null;
}
