import { describe, expect, it } from 'vitest';
import {
  AccountNotActiveError,
  AccountNotVerifiedError,
  CancellationError,
  ConflictError,
  ContractValidationError,
  ForbiddenError,
  NetworkError,
  NotFoundError,
  ParkioApiError,
  RateLimitError,
  ServerError,
  TimeoutError,
  UnauthorizedError,
  UnknownSdkError,
  UserStatusUnavailableError,
  ValidationError,
  classifySdkError,
  deserializeParkioError,
  isParkioSdkError,
  serializeParkioError,
  toParkioError,
  toSdkError,
  type CorrelationId,
  type RequestId,
} from './index';

const requestId = '10000000-0000-4000-8000-000000000001' as RequestId;
const correlationId = '20000000-0000-4000-8000-000000000002' as CorrelationId;

const apiBody = {
  code: 'ACTIVE_SESSION_EXISTS',
  message: 'An active parking session already exists.',
  traceId: correlationId,
  timestamp: '2026-07-22T15:00:00Z',
  fieldErrors: [{ field: 'estimatedFee', message: 'must be a decimal string' }],
};

const bodyWithCode = (code: string) => ({ ...apiBody, code });
const apiOptions = { requestId, correlationId, retryAfterMs: 2_500 } as const;

describe('SDK error classification', () => {
  it.each([
    [400, ValidationError, 'validation', false],
    [404, NotFoundError, 'not-found', false],
    [409, ConflictError, 'conflict', false],
    [429, RateLimitError, 'rate-limit', true],
    [500, ServerError, 'server', true],
  ] as const)(
    'classifies HTTP %i without transport coupling',
    (status, expectedClass, expectedKind, retryable) => {
      const error = toParkioError(status, apiBody);

      expect(error).toBeInstanceOf(expectedClass);
      expect(classifySdkError(error)).toEqual({
        kind: expectedKind,
        code: apiBody.code,
        retryable,
        status,
      });
    },
  );

  it('preserves the ACCOUNT_NOT_ACTIVE lifecycle classification', () => {
    const error = toParkioError(403, bodyWithCode('ACCOUNT_NOT_ACTIVE'));

    expect(error).toBeInstanceOf(AccountNotActiveError);
    expect(classifySdkError(error).kind).toBe('account-state');
    expect(error.retryable).toBe(false);
  });

  it('normalizes unknown thrown values without exposing their message', () => {
    const source = new Error('internal storage path and token detail');
    const error = toSdkError(source, { requestId });

    expect(error).toBeInstanceOf(UnknownSdkError);
    expect(error.message).toBe('An unexpected error occurred.');
    expect(error.cause).toBe(source);
    expect(classifySdkError(source)).toEqual({
      kind: 'unknown',
      code: 'UNKNOWN_ERROR',
      retryable: false,
    });
  });

  it('uses an injectable fallback timestamp for malformed backend bodies', () => {
    const error = toParkioError(
      500,
      { internal: 'not the contract' },
      { fallbackTimestamp: '2026-07-22T16:00:00Z' },
    );

    expect(error).toBeInstanceOf(ServerError);
    expect(error.code).toBe('UNKNOWN_ERROR');
    expect(error.timestamp).toBe('2026-07-22T16:00:00Z');
  });
});

describe('SDK error serialization', () => {
  const errors = [
    new ParkioApiError(418, bodyWithCode('TEAPOT'), apiOptions),
    new AccountNotActiveError(bodyWithCode('ACCOUNT_NOT_ACTIVE'), apiOptions),
    new AccountNotVerifiedError(bodyWithCode('ACCOUNT_NOT_VERIFIED'), apiOptions),
    new ForbiddenError(bodyWithCode('FORBIDDEN'), apiOptions),
    new RateLimitError(bodyWithCode('RATE_LIMITED'), apiOptions),
    new UserStatusUnavailableError(bodyWithCode('USER_STATUS_UNAVAILABLE'), apiOptions),
    new UnauthorizedError(bodyWithCode('INVALID_TOKEN'), apiOptions),
    new ValidationError(422, bodyWithCode('VALIDATION_FAILED'), apiOptions),
    new NotFoundError(bodyWithCode('SPOT_NOT_FOUND'), apiOptions),
    new ConflictError(bodyWithCode('ACTIVE_SESSION_EXISTS'), apiOptions),
    new ServerError(500, bodyWithCode('INTERNAL_SERVER_ERROR'), apiOptions),
    new NetworkError('Offline', { requestId, correlationId }),
    new TimeoutError('Timed out', { requestId, correlationId, timeoutMs: 3_000 }),
    new CancellationError('Cancelled', { requestId, correlationId }),
    new ContractValidationError('Invalid response', { requestId, correlationId }),
    new UnknownSdkError('Unknown', { requestId, correlationId }),
  ];

  it.each(errors)('round-trips $name without losing frozen semantics', (error) => {
    const serialized = serializeParkioError(error);
    const restored = deserializeParkioError(serialized);

    expect(restored).not.toBeNull();
    expect(restored?.constructor).toBe(error.constructor);
    expect(restored?.name).toBe(error.name);
    expect(classifySdkError(restored)).toEqual(classifySdkError(error));
    expect(serializeParkioError(restored!)).toEqual(serialized);
  });

  it('omits stack, cause, and raw transport data', () => {
    const source = new Error('axios internals');
    const error = new RateLimitError(bodyWithCode('RATE_LIMITED'), {
      ...apiOptions,
      cause: source,
    });

    const serialized = serializeParkioError(error);

    expect(serialized).not.toHaveProperty('stack');
    expect(serialized).not.toHaveProperty('cause');
    expect(serialized).not.toHaveProperty('response');
  });

  it('rejects concrete errors that cannot satisfy their frozen variant', () => {
    expect(() => serializeParkioError(new ServerError(418, apiBody))).toThrow(TypeError);
    expect(() =>
      serializeParkioError(new AccountNotActiveError(bodyWithCode('FORBIDDEN'))),
    ).toThrow(TypeError);
  });

  it('recognizes both API and client-side errors through the common base', () => {
    expect(isParkioSdkError(new NetworkError())).toBe(true);
    expect(isParkioSdkError(new ParkioApiError(418, apiBody))).toBe(true);
    expect(isParkioSdkError(new Error('plain'))).toBe(false);
  });
});

describe('serialized SDK error semantic validation', () => {
  const valid = serializeParkioError(
    new ConflictError(bodyWithCode('ACTIVE_SESSION_EXISTS'), apiOptions),
  );
  const validNetwork = serializeParkioError(
    new NetworkError('Offline', { requestId, correlationId }),
  );

  it.each([
    ['unsupported version', { ...valid, version: 2 }],
    ['unknown name', { ...valid, name: 'FutureError' }],
    ['unknown kind', { ...valid, kind: 'future-kind' }],
    ['invalid timestamp', { ...valid, timestamp: 'not-a-date' }],
    ['status below error range', { ...valid, status: 399 }],
    ['status above HTTP range', { ...valid, status: 600 }],
    ['fractional status', { ...valid, status: 409.5 }],
    ['incoherent API name', { ...valid, name: 'NotFoundError' }],
    ['incoherent API kind', { ...valid, kind: 'server' }],
    ['incoherent retryability', { ...valid, retryable: true }],
    ['missing API trace identifier', { ...valid, traceId: undefined }],
    ['malformed field errors', { ...valid, fieldErrors: [{ field: 7, message: 'invalid' }] }],
    ['client error with HTTP status', { ...validNetwork, status: 500 }],
    ['client error with API timestamp', { ...validNetwork, timestamp: apiBody.timestamp }],
    ['client error with incompatible code', { ...validNetwork, code: 'TIMEOUT' }],
    ['client error with incompatible timeout', { ...validNetwork, timeoutMs: 1_000 }],
    ['unknown frozen field', { ...valid, futureMetadata: true }],
    [
      'unknown field-error metadata',
      { ...valid, fieldErrors: [{ field: 'spotId', message: 'invalid', internal: true }] },
    ],
  ])('rejects %s deterministically', (_label, serialized) => {
    expect(deserializeParkioError(serialized)).toBeNull();
    expect(deserializeParkioError(serialized)).toBeNull();
  });
});
