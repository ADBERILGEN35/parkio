import type { QueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import type { Profile } from '@parkio/types';
import { clearUserSessionQueries } from '@/lib/sessionQueryCache';
import { useAuthStore } from '@/state/authStore';

function sessionCacheMatchesUser(client: QueryClient, userId: string): boolean {
  const profile = client.getQueryData<Profile>(['me', 'profile']);
  return !profile || profile.authUserId === userId;
}

export function SessionQueryCacheSync({ client }: { client: QueryClient }) {
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const previousUserId = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    if (previousUserId.current === undefined) {
      previousUserId.current = userId;
      if (userId && !sessionCacheMatchesUser(client, userId)) {
        clearUserSessionQueries(client);
      }
      return;
    }
    if (previousUserId.current !== userId) {
      clearUserSessionQueries(client);
      previousUserId.current = userId;
    }
  }, [client, userId]);

  return null;
}