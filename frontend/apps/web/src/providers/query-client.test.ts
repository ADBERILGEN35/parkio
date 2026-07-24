import {
  CancellationError,
  ForbiddenError,
  RateLimitError,
  UnauthorizedError,
  ValidationError,
} from '@parkio/api-client';
import { describe, expect, it } from 'vitest';
import {
  createWebQueryClient,
  shouldRetryQuery,
  WEB_QUERY_CLIENT_POLICY,
} from './query-client';

function errorBody(code: string) {
  return {
    code,
    message: code,
    traceId: 'trace-query-client',
    timestamp: '2026-07-23T10:00:00Z',
  };
}

describe('createWebQueryClient policy', () => {
  it('exposes documented global defaults', () => {
    expect(WEB_QUERY_CLIENT_POLICY.queries.staleTime).toBe(30_000);
    expect(WEB_QUERY_CLIENT_POLICY.queries.gcTime).toBe(300_000);
    expect(WEB_QUERY_CLIENT_POLICY.mutations.retry).toBe(0);
  });

  it('creates a client that is stable across repeated calls (no singleton)', () => {
    const a = createWebQueryClient();
    const b = createWebQueryClient();
    expect(a).not.toBe(b);
  });

  it('does not retry authentication, authorization, or validation failures', () => {
    expect(shouldRetryQuery(0, new UnauthorizedError(errorBody('INVALID_TOKEN')))).toBe(false);
    expect(shouldRetryQuery(0, new ForbiddenError(errorBody('FORBIDDEN')))).toBe(false);
    expect(shouldRetryQuery(0, new ValidationError(400, errorBody('VALIDATION_FAILED')))).toBe(
      false,
    );
  });

  it('does not retry cancelled requests', () => {
    expect(shouldRetryQuery(0, new CancellationError())).toBe(false);
  });

  it('retries rate-limit failures a limited number of times', () => {
    const error = new RateLimitError(errorBody('RATE_LIMITED'));
    expect(shouldRetryQuery(0, error)).toBe(true);
    expect(shouldRetryQuery(1, error)).toBe(true);
    expect(shouldRetryQuery(2, error)).toBe(false);
  });
});
