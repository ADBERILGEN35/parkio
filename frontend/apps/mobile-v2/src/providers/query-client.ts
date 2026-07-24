import {
  AccountNotActiveError,
  CancellationError,
  ForbiddenError,
  isParkioApiError,
  RateLimitError,
  UnauthorizedError,
  UserStatusUnavailableError,
  ValidationError,
} from '@parkio/api-client';
import { QueryClient } from '@tanstack/react-query';

/**
 * Documented Mobile QueryClient defaults (WP-07). Domain hooks may override
 * staleTime / gcTime when product behavior requires it.
 */
export const MOBILE_QUERY_CLIENT_POLICY = {
  queries: {
    staleTime: 15_000,
    gcTime: 5 * 60_000,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    refetchOnMount: true,
    networkMode: 'online' as const,
  },
  mutations: {
    retry: false as const,
  },
} as const;

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  // Aborts are intentional (route change / unmount / superseded query) — never retry.
  if (error instanceof CancellationError) {
    return false;
  }
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

/** Creates the Mobile query configuration without introducing shared mutable state. */
export function createMobileQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        ...MOBILE_QUERY_CLIENT_POLICY.queries,
        retry: shouldRetryQuery,
      },
      mutations: {
        ...MOBILE_QUERY_CLIENT_POLICY.mutations,
      },
    },
  });
}
