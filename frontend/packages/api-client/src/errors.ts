import type { AxiosError } from 'axios';
import type { ApiError } from '@parkio/types';
import { apiErrorSchema } from '@parkio/validation';

export class ParkioApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string;
  readonly timestamp: string;
  readonly fieldErrors?: ApiError['fieldErrors'];

  constructor(status: number, body: ApiError) {
    super(body.message);
    this.name = 'ParkioApiError';
    this.status = status;
    this.code = body.code;
    this.traceId = body.traceId;
    this.timestamp = body.timestamp;
    this.fieldErrors = body.fieldErrors;
  }
}

export class AccountNotActiveError extends ParkioApiError {
  constructor(body: ApiError) {
    super(403, body);
    this.name = 'AccountNotActiveError';
  }
}

export class ForbiddenError extends ParkioApiError {
  constructor(body: ApiError) {
    super(403, body);
    this.name = 'ForbiddenError';
  }
}

export class RateLimitError extends ParkioApiError {
  constructor(body: ApiError) {
    super(429, body);
    this.name = 'RateLimitError';
  }
}

export class UserStatusUnavailableError extends ParkioApiError {
  constructor(body: ApiError) {
    super(503, body);
    this.name = 'UserStatusUnavailableError';
  }
}

export class UnauthorizedError extends ParkioApiError {
  constructor(body: ApiError) {
    super(401, body);
    this.name = 'UnauthorizedError';
  }
}

export function parseApiError(data: unknown): ApiError | null {
  const parsed = apiErrorSchema.safeParse(data);
  return parsed.success ? parsed.data : null;
}

export function toParkioError(status: number, data: unknown): ParkioApiError {
  const body = parseApiError(data) ?? {
    code: 'UNKNOWN_ERROR',
    message: 'An unexpected error occurred.',
    traceId: '',
    timestamp: new Date().toISOString(),
  };

  if (status === 401) return new UnauthorizedError(body);
  if (status === 403 && body.code === 'ACCOUNT_NOT_ACTIVE') return new AccountNotActiveError(body);
  if (status === 403) return new ForbiddenError(body);
  if (status === 429) return new RateLimitError(body);
  if (status === 503 && body.code === 'USER_STATUS_UNAVAILABLE') {
    return new UserStatusUnavailableError(body);
  }

  return new ParkioApiError(status, body);
}

export function isParkioApiError(error: unknown): error is ParkioApiError {
  return error instanceof ParkioApiError;
}

export function getAxiosParkioError(error: AxiosError): ParkioApiError {
  const status = error.response?.status ?? 500;
  return toParkioError(status, error.response?.data);
}
