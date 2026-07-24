import { QueryClient } from '@tanstack/react-query';
import {
  AccountNotActiveError,
  ForbiddenError,
  isParkioApiError,
  RateLimitError,
  UserStatusUnavailableError,
} from '@parkio/api-client';

function handleQueryError(error: unknown): void {
  if (!isParkioApiError(error)) return;

  if (error instanceof AccountNotActiveError) {
    console.warn('[auth] Account not active', error.traceId);
    return;
  }
  if (error instanceof ForbiddenError) {
    console.warn('[auth] Forbidden', error.traceId);
    return;
  }
  if (error instanceof RateLimitError) {
    console.warn('[api] Rate limited — retry later', error.traceId);
    return;
  }
  if (error instanceof UserStatusUnavailableError) {
    console.warn('[api] User status unavailable — transient', error.traceId);
  }
}

/** Creates the existing Web query configuration without introducing shared mutable state. */
export function createWebQueryClient(): QueryClient {
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
        staleTime: 30_000,
      },
      mutations: {
        onError: handleQueryError,
      },
    },
  });
}
