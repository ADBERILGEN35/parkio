import { RateLimitError } from '@parkio/api-client';
import { isPublicSearchRateLimited } from '../isPublicSearchRateLimited';

describe('isPublicSearchRateLimited', () => {
  it('detects RateLimitError', () => {
    const error = new RateLimitError({
      code: 'RATE_LIMITED',
      message: 'Too many requests',
      timestamp: new Date().toISOString(),
      traceId: 't1',
    });
    expect(isPublicSearchRateLimited(error)).toBe(true);
  });

  it('ignores other errors', () => {
    expect(isPublicSearchRateLimited(new Error('boom'))).toBe(false);
    expect(isPublicSearchRateLimited(null)).toBe(false);
  });
});
