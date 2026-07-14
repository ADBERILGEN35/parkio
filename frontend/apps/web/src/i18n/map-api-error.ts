import type { TFunction } from 'i18next';
import type { ParkioApiError } from '@parkio/api-client';
import { isParkioApiError } from '@parkio/api-client';

const ERROR_CODE_KEYS: Record<string, string> = {
  EMAIL_ALREADY_EXISTS: 'errors:auth.emailAlreadyExists',
  INVALID_CREDENTIALS: 'errors:auth.invalidCredentials',
  EMAIL_NOT_VERIFIED: 'errors:auth.accountNotVerified',
  ACCOUNT_NOT_ACTIVE: 'errors:auth.accountSuspended',
  ACCOUNT_SUSPENDED: 'errors:auth.accountSuspended',
  RATE_LIMITED: 'errors:common.rateLimited',
  VALIDATION_ERROR: 'errors:common.validationError',
};

export function mapApiErrorCode(error: ParkioApiError, t: TFunction): string | null {
  const key = ERROR_CODE_KEYS[error.code];
  if (key) return t(key);
  return null;
}

export function describeLocalizedApiError(
  error: unknown,
  t: TFunction,
  fallbackKey = 'errors:common.generic',
): { message: string; code?: string; traceId?: string } {
  if (isParkioApiError(error)) {
    const mapped = mapApiErrorCode(error, t);
    if (mapped) {
      return { message: mapped, code: error.code, traceId: error.traceId || undefined };
    }
    if (error.status === 401) {
      return { message: t('errors:common.sessionExpired'), code: error.code, traceId: error.traceId || undefined };
    }
    if (error.status === 403) {
      return { message: t('errors:common.forbidden'), code: error.code, traceId: error.traceId || undefined };
    }
    if (error.status === 404) {
      return { message: t('errors:common.notFound'), code: error.code, traceId: error.traceId || undefined };
    }
    if (error.status === 429) {
      return { message: t('errors:common.rateLimited'), code: error.code, traceId: error.traceId || undefined };
    }
    if (error.status >= 500) {
      return { message: t('errors:common.serviceUnavailable'), code: error.code, traceId: error.traceId || undefined };
    }
    return {
      message: error.message || t(fallbackKey),
      code: error.code,
      traceId: error.traceId || undefined,
    };
  }
  return { message: t(fallbackKey) };
}