import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import {
  AccountNotActiveError,
  ForbiddenError,
  isParkioApiError,
  RateLimitError,
  UserStatusUnavailableError,
} from '@parkio/api-client';
import { queryClient } from './query-client';

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
    return;
  }
}

queryClient.setDefaultOptions({
  mutations: {
    onError: handleQueryError,
  },
});

export function QueryProvider({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
