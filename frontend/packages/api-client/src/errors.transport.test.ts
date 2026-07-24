import { CanceledError } from 'axios';
import { describe, expect, it } from 'vitest';
import {
  CancellationError,
  NetworkError,
  TimeoutError,
  ValidationError,
  getAxiosParkioError,
} from './errors';

describe('getAxiosParkioError transport mapping', () => {
  it('maps aborted Axios requests to CancellationError (not 5xx)', () => {
    const error = new CanceledError('canceled');
    const mapped = getAxiosParkioError(error);
    expect(mapped).toBeInstanceOf(CancellationError);
    expect(mapped.retryable).toBe(false);
  });

  it('maps AbortError-shaped failures to CancellationError', () => {
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    expect(getAxiosParkioError(abort)).toBeInstanceOf(CancellationError);
  });

  it('maps ECONNABORTED to TimeoutError', () => {
    const timeout = Object.assign(new Error('timeout of 30000ms exceeded'), {
      isAxiosError: true,
      code: 'ECONNABORTED',
      config: { timeout: 30_000 },
      toJSON: () => ({}),
      name: 'AxiosError',
    });
    const mapped = getAxiosParkioError(timeout);
    expect(mapped).toBeInstanceOf(TimeoutError);
    expect(mapped.retryable).toBe(true);
  });

  it('maps offline / no-response Axios failures to NetworkError', () => {
    const offline = Object.assign(new Error('Network Error'), {
      isAxiosError: true,
      code: 'ERR_NETWORK',
      toJSON: () => ({}),
      name: 'AxiosError',
    });
    const mapped = getAxiosParkioError(offline);
    expect(mapped).toBeInstanceOf(NetworkError);
    expect(mapped.retryable).toBe(true);
  });

  it('still maps API envelopes with status codes', () => {
    const apiError = Object.assign(new Error('Request failed'), {
      isAxiosError: true,
      response: {
        status: 422,
        data: {
          code: 'VALIDATION_FAILED',
          message: 'bad',
          traceId: 't1',
          timestamp: '2026-07-24T00:00:00Z',
        },
        statusText: 'Unprocessable',
        headers: {},
        config: {},
      },
      toJSON: () => ({}),
      name: 'AxiosError',
    });
    expect(getAxiosParkioError(apiError)).toBeInstanceOf(ValidationError);
  });
});
