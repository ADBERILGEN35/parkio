import { describe, expect, it } from 'vitest';
import {
  AccountNotActiveError,
  ForbiddenError,
  ParkioApiError,
  RateLimitError,
  UnauthorizedError,
  UserStatusUnavailableError,
  parseApiError,
  toParkioError,
} from './errors';

const body = {
  code: 'SPOT_NOT_FOUND',
  message: 'Spot not found',
  traceId: 'trace-1',
  timestamp: '2026-06-11T10:00:00Z',
};

describe('parseApiError', () => {
  it('parses a standard ApiError body', () => {
    const fieldErrors = [{ field: 'email', message: 'must not be blank' }];
    expect(parseApiError({ ...body, fieldErrors })).toEqual({ ...body, fieldErrors });
  });

  it('returns null for non-ApiError payloads', () => {
    expect(parseApiError(undefined)).toBeNull();
    expect(parseApiError('oops')).toBeNull();
    expect(parseApiError({ error: 'not the contract' })).toBeNull();
  });
});

describe('toParkioError', () => {
  it('maps a standard body onto ParkioApiError fields', () => {
    const error = toParkioError(404, body);
    expect(error).toBeInstanceOf(ParkioApiError);
    expect(error.status).toBe(404);
    expect(error.code).toBe('SPOT_NOT_FOUND');
    expect(error.message).toBe('Spot not found');
    expect(error.traceId).toBe('trace-1');
  });

  it('maps 401 to UnauthorizedError', () => {
    expect(toParkioError(401, { ...body, code: 'INVALID_TOKEN' })).toBeInstanceOf(
      UnauthorizedError,
    );
  });

  it('maps 403 ACCOUNT_NOT_ACTIVE to AccountNotActiveError', () => {
    expect(toParkioError(403, { ...body, code: 'ACCOUNT_NOT_ACTIVE' })).toBeInstanceOf(
      AccountNotActiveError,
    );
  });

  it('maps other 403s to ForbiddenError', () => {
    const error = toParkioError(403, { ...body, code: 'FORBIDDEN' });
    expect(error).toBeInstanceOf(ForbiddenError);
    expect(error).not.toBeInstanceOf(AccountNotActiveError);
  });

  it('maps 429 to RateLimitError', () => {
    expect(toParkioError(429, { ...body, code: 'RATE_LIMITED' })).toBeInstanceOf(RateLimitError);
  });

  it('maps 503 USER_STATUS_UNAVAILABLE to UserStatusUnavailableError', () => {
    expect(toParkioError(503, { ...body, code: 'USER_STATUS_UNAVAILABLE' })).toBeInstanceOf(
      UserStatusUnavailableError,
    );
  });

  it('keeps other 503s as plain ParkioApiError', () => {
    const error = toParkioError(503, { ...body, code: 'SERVICE_UNAVAILABLE' });
    expect(error).toBeInstanceOf(ParkioApiError);
    expect(error).not.toBeInstanceOf(UserStatusUnavailableError);
  });

  it('falls back to UNKNOWN_ERROR for unparseable bodies (network/unknown)', () => {
    const error = toParkioError(500, undefined);
    expect(error.code).toBe('UNKNOWN_ERROR');
    expect(error.status).toBe(500);
    expect(error.traceId).toBe('');
  });
});
