import {
  AccountNotActiveError,
  AccountNotVerifiedError,
  RateLimitError,
  UnauthorizedError,
  UserStatusUnavailableError,
  isParkioApiError,
} from '@parkio/api-client';
import type { ApiError } from '@parkio/types';

export interface FriendlyError {
  message: string;
  traceId?: string;
  fieldErrors?: ApiError['fieldErrors'];
}

/** i18next-compatible translator for auth API error mapping. */
export type AuthErrorTranslate = (key: string, options?: Record<string, unknown>) => string;

/**
 * Maps API errors from auth form submissions to user-friendly messages,
 * preserving traceId and validation fieldErrors for display.
 *
 * `t` is required: pass the one from useTranslation([..., 'errors']) so the
 * messages follow the active locale. An English-only fallback once made the
 * change-password errors English in every locale (#643).
 */
export function describeAuthError(
  error: unknown,
  fallback: string,
  t: AuthErrorTranslate,
): FriendlyError {
  if (error instanceof AccountNotActiveError) {
    return {
      message: t('errors:auth.accountSuspended'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof AccountNotVerifiedError) {
    return {
      message: t('errors:auth.accountNotVerified'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof UnauthorizedError) {
    return {
      message: t('errors:auth.invalidCredentials'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof RateLimitError) {
    return {
      message: t('errors:auth.rateLimited'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof UserStatusUnavailableError) {
    return {
      message: t('errors:auth.serviceUnavailable'),
      traceId: error.traceId || undefined,
    };
  }
  if (isParkioApiError(error)) {
    if (error.status >= 500) {
      return {
        message: t('errors:auth.serviceUnavailable'),
        traceId: error.traceId || undefined,
      };
    }
    return {
      message: error.message,
      traceId: error.traceId || undefined,
      fieldErrors: error.fieldErrors,
    };
  }
  return { message: fallback };
}
