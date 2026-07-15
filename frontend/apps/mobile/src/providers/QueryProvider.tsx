import {
  isParkioApiError,
  RateLimitError,
  UserStatusUnavailableError,
} from '@parkio/api-client';
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import { SessionQueryCacheSync } from './SessionQueryCacheSync';
import { LocaleBootstrap } from '@/i18n/LocaleBootstrap';

/**
 * TanStack Query provider with mobile-appropriate defaults.
 *
 * - Retries align with web: skip most 4xx; allow limited retries for rate-limit /
 *   transient user-status unavailability; otherwise at most one retry.
 * - `refetchOnReconnect` keeps data fresh after the network returns.
 * - App focus is bridged from React Native's AppState so queries can refetch when
 *   the app is brought to the foreground (web's `window.focus` has no RN analog).
 */
function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => {
          if (error instanceof RateLimitError || error instanceof UserStatusUnavailableError) {
            return failureCount < 2;
          }
          if (isParkioApiError(error) && error.status >= 400 && error.status < 500) {
            return false;
          }
          return failureCount < 1;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
        staleTime: 30_000,
        refetchOnReconnect: true,
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: 0,
      },
    },
  });
}

export function QueryProvider({ children }: { children: ReactNode }) {
  const [client] = useState(createQueryClient);

  useEffect(() => {
    const onChange = (status: AppStateStatus) => {
      focusManager.setFocused(status === 'active');
    };
    const subscription = AppState.addEventListener('change', onChange);
    return () => subscription.remove();
  }, []);

  return (
    <QueryClientProvider client={client}>
      <SessionQueryCacheSync client={client} />
      <LocaleBootstrap />
      {children}
    </QueryClientProvider>
  );
}
