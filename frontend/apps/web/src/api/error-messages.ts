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

/**
 * Maps API errors from auth form submissions to user-friendly messages,
 * preserving traceId and validation fieldErrors for display.
 */
export function describeAuthError(error: unknown, fallback: string): FriendlyError {
  if (error instanceof AccountNotActiveError) {
    return { message: 'Your account is suspended.', traceId: error.traceId || undefined };
  }
  if (error instanceof AccountNotVerifiedError) {
    return {
      message: 'Please verify your email before signing in.',
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof UnauthorizedError) {
    return { message: 'Invalid email or password.', traceId: error.traceId || undefined };
  }
  if (error instanceof RateLimitError) {
    return {
      message: 'Too many attempts. Please wait a moment and try again.',
      traceId: error.traceId || undefined,
    };
  }
  if (error instanceof UserStatusUnavailableError) {
    return {
      message: 'Service is temporarily unavailable. Please try again.',
      traceId: error.traceId || undefined,
    };
  }
  if (isParkioApiError(error)) {
    if (error.status >= 500) {
      return {
        message: 'Service is temporarily unavailable. Please try again.',
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
