import {
  CancellationError,
  ForbiddenError,
  NetworkError,
  RateLimitError,
  UnauthorizedError,
  ValidationError,
} from '@parkio/api-client';
import { createMobileQueryClient, shouldRetryQuery } from '../query-client';

function errorBody(code: string) {
  return {
    code,
    message: code,
    traceId: 'trace-query-client',
    timestamp: '2026-07-24T10:00:00Z',
  };
}

describe('WP-07 mobile QueryClient policy', () => {
  it('never retries CancellationError', () => {
    expect(shouldRetryQuery(0, new CancellationError())).toBe(false);
  });

  it('does not retry unauthorized / forbidden / validation', () => {
    expect(shouldRetryQuery(0, new UnauthorizedError(errorBody('INVALID_TOKEN')))).toBe(false);
    expect(shouldRetryQuery(0, new ForbiddenError(errorBody('FORBIDDEN')))).toBe(false);
    expect(shouldRetryQuery(0, new ValidationError(400, errorBody('VALIDATION_FAILED')))).toBe(false);
  });

  it('bounds rate-limit retries', () => {
    const error = new RateLimitError(errorBody('RATE_LIMITED'));
    expect(shouldRetryQuery(0, error)).toBe(true);
    expect(shouldRetryQuery(2, error)).toBe(false);
  });

  it('creates a configured QueryClient factory', () => {
    const client = createMobileQueryClient();
    expect(client).toBeTruthy();
    expect(client.getDefaultOptions().queries?.retry).toBe(shouldRetryQuery);
  });

  it('treats NetworkError as retryable once', () => {
    expect(shouldRetryQuery(0, new NetworkError('offline'))).toBe(true);
    expect(shouldRetryQuery(1, new NetworkError('offline'))).toBe(false);
  });
});