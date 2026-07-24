import {
  AccountNotActiveError,
  ForbiddenError,
  isParkioApiError,
  RateLimitError,
  UnauthorizedError,
  UserStatusUnavailableError,
  ValidationError,
} from '@parkio/api-client';
import { QueryClient } from '@tanstack/react-query';

/**
 * Documented Web QueryClient defaults (WP-04). Domain hooks may override
 * staleTime / gcTime when product behavior requires it.
 */
export const WEB_QUERY_CLIENT_POLICY = {
  queries: {
    staleTime: 30_000,
    gcTime: 300_000,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    refetchOnMount: true,
    networkMode: 'online' as const,
  },
  mutations: {
    retry: 0 as const,
  },
} as const;

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (
    error instanceof UnauthorizedError ||
    error instanceof ForbiddenError ||
    error instanceof ValidationError ||
    error instanceof AccountNotActiveError
  ) {
    return false;
  }
  if (error instanceof RateLimitError || error instanceof UserStatusUnavailableError) {
    return failureCount < 2;
  }
  if (isParkioApiError(error) && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < 1;
}

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
        ...WEB_QUERY_CLIENT_POLICY.queries,
        retry: shouldRetryQuery,
      },
      mutations: {
        ...WEB_QUERY_CLIENT_POLICY.mutations,
        onError: handleQueryError,
      },
    },
  });
}
