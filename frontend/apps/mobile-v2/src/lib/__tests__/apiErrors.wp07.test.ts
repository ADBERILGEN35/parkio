import { CancellationError, NetworkError, TimeoutError } from '@parkio/api-client';
import { describeApiError } from '../apiErrors';

const t = (key: string) => key;

describe('WP-07 typed transport error mapping', () => {
  it('keeps cancellation silent', () => {
    const mapped = describeApiError(new CancellationError(), t as never);
    expect(mapped.message).toBe('');
    expect(mapped.code).toBe('REQUEST_CANCELLED');
  });

  it('maps timeout and network failures', () => {
    expect(describeApiError(new TimeoutError('slow'), t as never).code).toBe('TIMEOUT');
    expect(describeApiError(new NetworkError('offline'), t as never).code).toBe('NETWORK_ERROR');
  });
});