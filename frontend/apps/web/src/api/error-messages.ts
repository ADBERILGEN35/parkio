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

const EN_FALLBACKS = {
  'errors:auth.accountSuspended': 'Your account is suspended.',
  'errors:auth.accountNotVerified': 'Please verify your email before signing in.',
  'errors:auth.invalidCredentials': 'Invalid email or password.',
  'errors:auth.rateLimited': 'Too many attempts. Please wait a moment and try again.',
  'errors:auth.serviceUnavailable': 'Service is temporarily unavailable. Please try again.',
} as const;

function resolveMessage(t: AuthErrorTranslate | undefined, key: keyof typeof EN_FALLBACKS): string {
  if (t) return t(key);
  return EN_FALLBACKS[key];
}

/**
 * Maps API errors from auth form submissions to user-friendly messages,
 * preserving traceId and validation fieldErrors for display.
 *
 * Pass `t` from useTranslation(['errors', ...]) so messages follow the active locale.
 * When `t` is omitted, English fallbacks are used (backward compatible).
 */
export function describeAuthError(
  error: unknown,
  fallback: string,
  t?: AuthErrorTranslate,
): FriendlyError {
  if (error instanceof AccountNotActiveError) {
    return {
      message: resolveMessage(t, 'errors:auth.accountSuspended'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof AccountNotVerifiedError) {
    return {
      message: resolveMessage(t, 'errors:auth.accountNotVerified'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof UnauthorizedError) {
    return {
      message: resolveMessage(t, 'errors:auth.invalidCredentials'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof RateLimitError) {
    return {
      message: resolveMessage(t, 'errors:auth.rateLimited'),
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof UserStatusUnavailableError) {
    return {
      message: resolveMessage(t, 'errors:auth.serviceUnavailable'),
      traceId: error.traceId || undefined,
    };
  }
  if (isParkioApiError(error)) {
    if (error.status >= 500) {
      return {
        message: resolveMessage(t, 'errors:auth.serviceUnavailable'),
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
