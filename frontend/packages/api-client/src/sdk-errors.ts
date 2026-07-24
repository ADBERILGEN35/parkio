import type { ApiError, FieldError } from '@parkio/types';
import { apiErrorSchema } from '@parkio/validation';
import type { CorrelationId, RequestId } from './correlation';

export const SDK_ERROR_KINDS = [
  'api',
  'authentication',
  'authorization',
  'account-state',
  'validation',
  'not-found',
  'conflict',
  'rate-limit',
  'server',
  'network',
  'timeout',
  'cancellation',
  'contract',
  'unknown',
] as const;

export type SdkErrorKind = (typeof SDK_ERROR_KINDS)[number];

export interface SdkErrorContext {
  readonly requestId?: RequestId;
  readonly correlationId?: CorrelationId;
}

export interface SdkErrorOptions extends SdkErrorContext {
  readonly cause?: unknown;
}

interface BaseSdkErrorOptions extends SdkErrorOptions {
  readonly kind: SdkErrorKind;
  readonly code: string;
  readonly retryable: boolean;
}

/** Framework-neutral base for every error produced by the shared SDK. */
export abstract class ParkioSdkError extends Error {
  readonly kind: SdkErrorKind;
  readonly code: string;
  readonly retryable: boolean;
  readonly requestId?: RequestId;
  readonly correlationId?: CorrelationId;

  protected constructor(message: string, options: BaseSdkErrorOptions) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.kind = options.kind;
    this.code = options.code;
    this.retryable = options.retryable ?? false;
    this.requestId = options.requestId;
    this.correlationId = options.correlationId;
  }
}

export interface ParkioApiErrorOptions extends SdkErrorOptions {
  readonly retryAfterMs?: number;
}

/** Validated backend ApiError together with its HTTP classification. */
export class ParkioApiError extends ParkioSdkError {
  readonly status: number;
  readonly traceId: string;
  readonly timestamp: string;
  readonly fieldErrors?: ApiError['fieldErrors'];
  readonly retryAfterMs?: number;

  constructor(status: number, body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(body.message, {
      ...options,
      code: body.code,
      kind: kindForStatus(status, body.code),
      retryable: isRetryableStatus(status),
      correlationId: options.correlationId ?? nonEmptyCorrelationId(body.traceId),
    });
    this.name = 'ParkioApiError';
    this.status = status;
    this.traceId = body.traceId;
    this.timestamp = body.timestamp;
    this.fieldErrors = body.fieldErrors;
    this.retryAfterMs = normalizeNonNegativeNumber(options.retryAfterMs);
  }
}

export class AccountNotActiveError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(403, body, options);
    this.name = 'AccountNotActiveError';
  }
}

export class AccountNotVerifiedError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(403, body, options);
    this.name = 'AccountNotVerifiedError';
  }
}

export class ForbiddenError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(403, body, options);
    this.name = 'ForbiddenError';
  }
}

export class RateLimitError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(429, body, options);
    this.name = 'RateLimitError';
  }
}

export class UserStatusUnavailableError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(503, body, options);
    this.name = 'UserStatusUnavailableError';
  }
}

export class UnauthorizedError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(401, body, options);
    this.name = 'UnauthorizedError';
  }
}

export class ValidationError extends ParkioApiError {
  constructor(status: 400 | 422, body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(status, body, options);
    this.name = 'ValidationError';
  }
}

export class NotFoundError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(404, body, options);
    this.name = 'NotFoundError';
  }
}

export class ConflictError extends ParkioApiError {
  constructor(body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(409, body, options);
    this.name = 'ConflictError';
  }
}

export class ServerError extends ParkioApiError {
  constructor(status: number, body: ApiError, options: ParkioApiErrorOptions = {}) {
    super(status, body, options);
    this.name = 'ServerError';
  }
}

export class NetworkError extends ParkioSdkError {
  constructor(message = 'Network request failed.', options: SdkErrorOptions = {}) {
    super(message, { ...options, kind: 'network', code: 'NETWORK_ERROR', retryable: true });
    this.name = 'NetworkError';
  }
}

export interface TimeoutErrorOptions extends SdkErrorOptions {
  readonly timeoutMs?: number;
}

export class TimeoutError extends ParkioSdkError {
  readonly timeoutMs?: number;

  constructor(message = 'Request timed out.', options: TimeoutErrorOptions = {}) {
    super(message, { ...options, kind: 'timeout', code: 'TIMEOUT', retryable: true });
    this.name = 'TimeoutError';
    this.timeoutMs = normalizeNonNegativeNumber(options.timeoutMs);
  }
}

export class CancellationError extends ParkioSdkError {
  constructor(message = 'Request was cancelled.', options: SdkErrorOptions = {}) {
    super(message, {
      ...options,
      kind: 'cancellation',
      code: 'REQUEST_CANCELLED',
      retryable: false,
    });
    this.name = 'CancellationError';
  }
}

export class ContractValidationError extends ParkioSdkError {
  constructor(message = 'Response did not match the frozen contract.', options: SdkErrorOptions = {}) {
    super(message, {
      ...options,
      kind: 'contract',
      code: 'CONTRACT_VALIDATION_FAILED',
      retryable: false,
    });
    this.name = 'ContractValidationError';
  }
}

export class UnknownSdkError extends ParkioSdkError {
  constructor(message = 'An unexpected error occurred.', options: SdkErrorOptions = {}) {
    super(message, { ...options, kind: 'unknown', code: 'UNKNOWN_ERROR', retryable: false });
    this.name = 'UnknownSdkError';
  }
}

export interface ApiErrorMappingOptions extends ParkioApiErrorOptions {
  readonly fallbackTimestamp?: string;
}

export interface SdkErrorClassification {
  readonly kind: SdkErrorKind;
  readonly code: string;
  readonly retryable: boolean;
  readonly status?: number;
}

export const SERIALIZED_PARKIO_ERROR_VERSION = 1 as const;

export const SERIALIZED_PARKIO_ERROR_NAMES = [
  'ParkioApiError',
  'AccountNotActiveError',
  'AccountNotVerifiedError',
  'ForbiddenError',
  'RateLimitError',
  'UserStatusUnavailableError',
  'UnauthorizedError',
  'ValidationError',
  'NotFoundError',
  'ConflictError',
  'ServerError',
  'NetworkError',
  'TimeoutError',
  'CancellationError',
  'ContractValidationError',
  'UnknownSdkError',
] as const;

export type SerializedParkioErrorName = (typeof SERIALIZED_PARKIO_ERROR_NAMES)[number];

/** Safe error representation for logs, telemetry, and process boundaries. */
export interface SerializedParkioError {
  readonly version: typeof SERIALIZED_PARKIO_ERROR_VERSION;
  readonly name: SerializedParkioErrorName;
  readonly kind: SdkErrorKind;
  readonly code: string;
  readonly message: string;
  readonly retryable: boolean;
  readonly requestId?: RequestId;
  readonly correlationId?: CorrelationId;
  readonly status?: number;
  readonly traceId?: string;
  readonly timestamp?: string;
  readonly fieldErrors?: readonly FieldError[];
  readonly retryAfterMs?: number;
  readonly timeoutMs?: number;
}

export function parseApiError(data: unknown): ApiError | null {
  const parsed = apiErrorSchema.safeParse(data);
  return parsed.success ? parsed.data : null;
}

/** Maps a validated backend error envelope without depending on an HTTP library. */
export function toParkioError(
  status: number,
  data: unknown,
  options: ApiErrorMappingOptions = {},
): ParkioApiError {
  const body = parseApiError(data) ?? fallbackApiError(options.fallbackTimestamp);

  if (status === 400 || status === 422) return new ValidationError(status, body, options);
  if (status === 401) return new UnauthorizedError(body, options);
  if (status === 403 && body.code === 'ACCOUNT_NOT_VERIFIED') {
    return new AccountNotVerifiedError(body, options);
  }
  if (status === 403 && body.code === 'ACCOUNT_NOT_ACTIVE') {
    return new AccountNotActiveError(body, options);
  }
  if (status === 403) return new ForbiddenError(body, options);
  if (status === 404) return new NotFoundError(body, options);
  if (status === 409) return new ConflictError(body, options);
  if (status === 429) return new RateLimitError(body, options);
  if (status === 503 && body.code === 'USER_STATUS_UNAVAILABLE') {
    return new UserStatusUnavailableError(body, options);
  }
  if (status >= 500) return new ServerError(status, body, options);
  return new ParkioApiError(status, body, options);
}

export function isParkioSdkError(error: unknown): error is ParkioSdkError {
  return error instanceof ParkioSdkError;
}

export function isParkioApiError(error: unknown): error is ParkioApiError {
  return error instanceof ParkioApiError;
}

/** Converts an unknown thrown value into the stable SDK error hierarchy. */
export function toSdkError(error: unknown, context: SdkErrorContext = {}): ParkioSdkError {
  if (isParkioSdkError(error)) return error;
  return new UnknownSdkError(undefined, { ...context, cause: error });
}

export function classifySdkError(error: unknown): SdkErrorClassification {
  const sdkError = isParkioSdkError(error) ? error : toSdkError(error);
  return {
    kind: sdkError.kind,
    code: sdkError.code,
    retryable: sdkError.retryable,
    ...(sdkError instanceof ParkioApiError ? { status: sdkError.status } : {}),
  };
}

export function serializeParkioError(error: ParkioSdkError): SerializedParkioError {
  const name = serializedNameForError(error);
  if (error.name !== name) {
    throw new TypeError('SDK error name does not match its concrete class.');
  }

  const serialized: SerializedParkioError = {
    version: SERIALIZED_PARKIO_ERROR_VERSION,
    name,
    kind: error.kind,
    code: error.code,
    message: error.message,
    retryable: error.retryable,
    ...optional('requestId', error.requestId),
    ...optional('correlationId', error.correlationId),
    ...(error instanceof ParkioApiError
      ? {
          status: error.status,
          traceId: error.traceId,
          timestamp: error.timestamp,
          ...optional('fieldErrors', error.fieldErrors),
          ...optional('retryAfterMs', error.retryAfterMs),
        }
      : {}),
    ...(error instanceof TimeoutError ? optional('timeoutMs', error.timeoutMs) : {}),
  };

  const validated = parseSerializedParkioError(serialized);
  if (!validated) {
    throw new TypeError('SDK error cannot be represented by the frozen serialization contract.');
  }
  return validated;
}

/** Returns null for malformed or unknown serialized-error versions. */
export function deserializeParkioError(value: unknown): ParkioSdkError | null {
  const serialized = parseSerializedParkioError(value);
  if (!serialized) return null;

  const context: SdkErrorContext = {
    ...optional('requestId', serialized.requestId),
    ...optional('correlationId', serialized.correlationId),
  };

  if (hasSerializedApiFields(serialized)) {
    const body: ApiError = {
      code: serialized.code,
      message: serialized.message,
      traceId: serialized.traceId,
      timestamp: serialized.timestamp,
      ...(serialized.fieldErrors === undefined
        ? {}
        : { fieldErrors: serialized.fieldErrors.map((fieldError) => ({ ...fieldError })) }),
    };
    const options: ParkioApiErrorOptions = {
      ...context,
      ...optional('retryAfterMs', serialized.retryAfterMs),
    };

    switch (serialized.name) {
      case 'ParkioApiError':
        return new ParkioApiError(serialized.status, body, options);
      case 'AccountNotActiveError':
        return new AccountNotActiveError(body, options);
      case 'AccountNotVerifiedError':
        return new AccountNotVerifiedError(body, options);
      case 'ForbiddenError':
        return new ForbiddenError(body, options);
      case 'RateLimitError':
        return new RateLimitError(body, options);
      case 'UserStatusUnavailableError':
        return new UserStatusUnavailableError(body, options);
      case 'UnauthorizedError':
        return new UnauthorizedError(body, options);
      case 'ValidationError':
        return new ValidationError(serialized.status as 400 | 422, body, options);
      case 'NotFoundError':
        return new NotFoundError(body, options);
      case 'ConflictError':
        return new ConflictError(body, options);
      case 'ServerError':
        return new ServerError(serialized.status, body, options);
      default:
        return null;
    }
  }

  switch (serialized.name) {
    case 'NetworkError':
      return new NetworkError(serialized.message, context);
    case 'TimeoutError':
      return new TimeoutError(serialized.message, {
        ...context,
        timeoutMs: serialized.timeoutMs,
      });
    case 'CancellationError':
      return new CancellationError(serialized.message, context);
    case 'ContractValidationError':
      return new ContractValidationError(serialized.message, context);
    case 'UnknownSdkError':
      return new UnknownSdkError(serialized.message, context);
    default:
      return null;
  }
}

function kindForStatus(status: number, code: string): SdkErrorKind {
  if (status === 400 || status === 422) return 'validation';
  if (status === 401) return 'authentication';
  if (
    status === 403 &&
    (code === 'ACCOUNT_NOT_ACTIVE' || code === 'ACCOUNT_NOT_VERIFIED')
  ) {
    return 'account-state';
  }
  if (status === 403) return 'authorization';
  if (status === 404) return 'not-found';
  if (status === 409) return 'conflict';
  if (status === 429) return 'rate-limit';
  if (status >= 500) return 'server';
  return 'api';
}

function isRetryableStatus(status: number): boolean {
  return status === 429 || status >= 500;
}

function fallbackApiError(timestamp = new Date().toISOString()): ApiError {
  return {
    code: 'UNKNOWN_ERROR',
    message: 'An unexpected error occurred.',
    traceId: '',
    timestamp,
  };
}

function nonEmptyCorrelationId(value: string): CorrelationId | undefined {
  return value.length > 0 ? (value as CorrelationId) : undefined;
}

function normalizeNonNegativeNumber(value: number | undefined): number | undefined {
  return value !== undefined && Number.isFinite(value) && value >= 0 ? value : undefined;
}

function optional<Key extends string, Value>(
  key: Key,
  value: Value | undefined,
): { [Property in Key]?: Value } {
  return value === undefined ? {} : ({ [key]: value } as { [Property in Key]: Value });
}

function parseSerializedParkioError(value: unknown): SerializedParkioError | null {
  if (!isRecord(value)) return null;
  if (value.version !== SERIALIZED_PARKIO_ERROR_VERSION) return null;
  if (!isSerializedParkioErrorName(value.name) || !isSdkErrorKind(value.kind)) return null;
  if (!hasOnlySerializedFields(value, value.name)) return null;
  if (typeof value.code !== 'string' || typeof value.message !== 'string') return null;
  if (typeof value.retryable !== 'boolean') return null;
  if (!isOptionalString(value.requestId) || !isOptionalString(value.correlationId)) return null;
  if (!isOptionalFiniteNumber(value.status) || !isOptionalFiniteNumber(value.retryAfterMs)) return null;
  if (!isOptionalFiniteNumber(value.timeoutMs)) return null;
  if (!isOptionalString(value.traceId) || !isOptionalString(value.timestamp)) return null;
  if (!isOptionalFieldErrors(value.fieldErrors)) return null;

  if (isApiErrorName(value.name)) {
    if (!isValidHttpErrorStatus(value.status)) return null;
    if (typeof value.traceId !== 'string' || typeof value.timestamp !== 'string') return null;
    if (value.timeoutMs !== undefined) return null;
    const body = {
      code: value.code,
      message: value.message,
      traceId: value.traceId,
      timestamp: value.timestamp,
      ...optional('fieldErrors', value.fieldErrors),
    };
    if (!apiErrorSchema.safeParse(body).success) return null;

    const expected = apiErrorDescriptor(value.status, value.code);
    if (
      (value.name !== 'ParkioApiError' && value.name !== expected.name) ||
      value.kind !== expected.kind ||
      value.retryable !== expected.retryable
    ) {
      return null;
    }
  } else {
    if (
      value.status !== undefined ||
      value.traceId !== undefined ||
      value.timestamp !== undefined ||
      value.fieldErrors !== undefined ||
      value.retryAfterMs !== undefined
    ) {
      return null;
    }
    const expected = clientErrorDescriptor(value.name);
    if (
      value.kind !== expected.kind ||
      value.code !== expected.code ||
      value.retryable !== expected.retryable
    ) {
      return null;
    }
    if (value.name !== 'TimeoutError' && value.timeoutMs !== undefined) return null;
  }

  return value as unknown as SerializedParkioError;
}

function serializedNameForError(error: ParkioSdkError): SerializedParkioErrorName {
  if (error instanceof AccountNotActiveError) return 'AccountNotActiveError';
  if (error instanceof AccountNotVerifiedError) return 'AccountNotVerifiedError';
  if (error instanceof ForbiddenError) return 'ForbiddenError';
  if (error instanceof RateLimitError) return 'RateLimitError';
  if (error instanceof UserStatusUnavailableError) return 'UserStatusUnavailableError';
  if (error instanceof UnauthorizedError) return 'UnauthorizedError';
  if (error instanceof ValidationError) return 'ValidationError';
  if (error instanceof NotFoundError) return 'NotFoundError';
  if (error instanceof ConflictError) return 'ConflictError';
  if (error instanceof ServerError) return 'ServerError';
  if (error instanceof ParkioApiError) return 'ParkioApiError';
  if (error instanceof NetworkError) return 'NetworkError';
  if (error instanceof TimeoutError) return 'TimeoutError';
  if (error instanceof CancellationError) return 'CancellationError';
  if (error instanceof ContractValidationError) return 'ContractValidationError';
  if (error instanceof UnknownSdkError) return 'UnknownSdkError';
  throw new TypeError('Unsupported SDK error class.');
}

type SerializedApiError = SerializedParkioError & {
  readonly status: number;
  readonly traceId: string;
  readonly timestamp: string;
};

function hasSerializedApiFields(value: SerializedParkioError): value is SerializedApiError {
  return (
    isApiErrorName(value.name) &&
    value.status !== undefined &&
    value.traceId !== undefined &&
    value.timestamp !== undefined
  );
}

function isSerializedParkioErrorName(value: unknown): value is SerializedParkioErrorName {
  return (
    typeof value === 'string' &&
    SERIALIZED_PARKIO_ERROR_NAMES.some((name) => name === value)
  );
}

function isApiErrorName(name: SerializedParkioErrorName): boolean {
  return ![
    'NetworkError',
    'TimeoutError',
    'CancellationError',
    'ContractValidationError',
    'UnknownSdkError',
  ].includes(name);
}

interface ApiErrorDescriptor {
  readonly name: SerializedParkioErrorName;
  readonly kind: SdkErrorKind;
  readonly retryable: boolean;
}

function apiErrorDescriptor(status: number, code: string): ApiErrorDescriptor {
  if (status === 400 || status === 422) {
    return { name: 'ValidationError', kind: 'validation', retryable: false };
  }
  if (status === 401) {
    return { name: 'UnauthorizedError', kind: 'authentication', retryable: false };
  }
  if (status === 403 && code === 'ACCOUNT_NOT_ACTIVE') {
    return { name: 'AccountNotActiveError', kind: 'account-state', retryable: false };
  }
  if (status === 403 && code === 'ACCOUNT_NOT_VERIFIED') {
    return { name: 'AccountNotVerifiedError', kind: 'account-state', retryable: false };
  }
  if (status === 403) {
    return { name: 'ForbiddenError', kind: 'authorization', retryable: false };
  }
  if (status === 404) {
    return { name: 'NotFoundError', kind: 'not-found', retryable: false };
  }
  if (status === 409) {
    return { name: 'ConflictError', kind: 'conflict', retryable: false };
  }
  if (status === 429) {
    return { name: 'RateLimitError', kind: 'rate-limit', retryable: true };
  }
  if (status === 503 && code === 'USER_STATUS_UNAVAILABLE') {
    return { name: 'UserStatusUnavailableError', kind: 'server', retryable: true };
  }
  if (status >= 500) {
    return { name: 'ServerError', kind: 'server', retryable: true };
  }
  return { name: 'ParkioApiError', kind: kindForStatus(status, code), retryable: false };
}

interface ClientErrorDescriptor {
  readonly kind: SdkErrorKind;
  readonly code: string;
  readonly retryable: boolean;
}

function clientErrorDescriptor(name: SerializedParkioErrorName): ClientErrorDescriptor {
  switch (name) {
    case 'NetworkError':
      return { kind: 'network', code: 'NETWORK_ERROR', retryable: true };
    case 'TimeoutError':
      return { kind: 'timeout', code: 'TIMEOUT', retryable: true };
    case 'CancellationError':
      return { kind: 'cancellation', code: 'REQUEST_CANCELLED', retryable: false };
    case 'ContractValidationError':
      return { kind: 'contract', code: 'CONTRACT_VALIDATION_FAILED', retryable: false };
    case 'UnknownSdkError':
      return { kind: 'unknown', code: 'UNKNOWN_ERROR', retryable: false };
    default:
      throw new TypeError('Expected a client-side SDK error name.');
  }
}

function isValidHttpErrorStatus(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 400 && value <= 599;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

const COMMON_SERIALIZED_FIELDS = [
  'version',
  'name',
  'kind',
  'code',
  'message',
  'retryable',
  'requestId',
  'correlationId',
] as const;

const API_SERIALIZED_FIELDS = [
  ...COMMON_SERIALIZED_FIELDS,
  'status',
  'traceId',
  'timestamp',
  'fieldErrors',
  'retryAfterMs',
] as const;

const TIMEOUT_SERIALIZED_FIELDS = [...COMMON_SERIALIZED_FIELDS, 'timeoutMs'] as const;

function hasOnlySerializedFields(
  value: Record<string, unknown>,
  name: SerializedParkioErrorName,
): boolean {
  const allowedFields = isApiErrorName(name)
    ? API_SERIALIZED_FIELDS
    : name === 'TimeoutError'
      ? TIMEOUT_SERIALIZED_FIELDS
      : COMMON_SERIALIZED_FIELDS;
  return Object.keys(value).every((key) => (allowedFields as readonly string[]).includes(key));
}

function isSdkErrorKind(value: unknown): value is SdkErrorKind {
  return typeof value === 'string' && SDK_ERROR_KINDS.some((kind) => kind === value);
}

function isOptionalString(value: unknown): value is string | undefined {
  return value === undefined || typeof value === 'string';
}

function isOptionalFiniteNumber(value: unknown): value is number | undefined {
  return value === undefined || (typeof value === 'number' && Number.isFinite(value) && value >= 0);
}

function isOptionalFieldErrors(value: unknown): value is readonly FieldError[] | undefined {
  return (
    value === undefined ||
    (Array.isArray(value) &&
      value.every(
        (fieldError) =>
          isRecord(fieldError) &&
          Object.keys(fieldError).every((key) => key === 'field' || key === 'message') &&
          typeof fieldError.field === 'string' &&
          typeof fieldError.message === 'string',
      ))
  );
}
