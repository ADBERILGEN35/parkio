import { isParkioApiError, ParkioApiError } from '@parkio/api-client';

/**
 * The fixed messages `toUserMessage` returns. They are English source copy, translated when
 * shown: AppText, which also renders the global toast, passes string children through `t` from
 * `@/i18n/LocaleProvider`, and `t` looks them up in `src/i18n/translations.ts`. Each message
 * therefore needs a Turkish entry there; a unit test fails for any message without one.
 */
export const ERROR_MESSAGES = {
  offline: 'You appear to be offline. Check your connection and try again.',
  generic: 'Something went wrong. Please try again.',
  checkDetails: 'Please check the details and try again.',
  invalidCredentials: 'Incorrect email or password.',
  sessionExpired: 'Your session has expired. Please sign in again.',
  forbidden: 'You don’t have access to do that.',
  notFound: 'We couldn’t find what you were looking for.',
  conflict: 'That conflicts with something that already exists.',
  photoTooLarge: 'That photo is too large to upload. Try a smaller one.',
  unsupportedImage: 'That image format isn’t supported. Use a JPEG, PNG, or WebP photo.',
  rateLimited: 'Too many attempts. Please wait a moment and try again.',
  serverError: 'Our servers are having a moment. Please try again shortly.',
} as const;

/**
 * Map any thrown value into a short, human, non-technical message suitable for a
 * toast or inline error. Mirrors the web app's friendly-error philosophy: never
 * surface stack traces, codes, or JSON to users.
 */
export function toUserMessage(error: unknown): string {
  if (isParkioApiError(error)) {
    return messageForApiError(error);
  }
  if (isNetworkError(error)) {
    return ERROR_MESSAGES.offline;
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return ERROR_MESSAGES.generic;
}

function messageForApiError(error: ParkioApiError): string {
  switch (error.status) {
    case 400:
    case 422:
      return firstFieldError(error) ?? ERROR_MESSAGES.checkDetails;
    case 401:
      // INVALID_CREDENTIALS is a failed password check, not an expired session: a wrong
      // password on login or account deletion, or a wrong current password on change-password.
      // Token 401s (MISSING_TOKEN, INVALID_TOKEN, TOKEN_REVOKED) mean the session has expired.
      return error.code === 'INVALID_CREDENTIALS'
        ? ERROR_MESSAGES.invalidCredentials
        : ERROR_MESSAGES.sessionExpired;
    case 403:
      return ERROR_MESSAGES.forbidden;
    case 404:
      return ERROR_MESSAGES.notFound;
    case 409:
      return error.message || ERROR_MESSAGES.conflict;
    case 413:
      return ERROR_MESSAGES.photoTooLarge;
    case 415:
      return ERROR_MESSAGES.unsupportedImage;
    case 429:
      return ERROR_MESSAGES.rateLimited;
    default:
      return error.status >= 500
        ? ERROR_MESSAGES.serverError
        : error.message || ERROR_MESSAGES.generic;
  }
}

function firstFieldError(error: ParkioApiError): string | null {
  const fieldErrors = error.fieldErrors;
  if (!fieldErrors) return null;
  const first = Object.values(fieldErrors)[0];
  if (Array.isArray(first)) return first[0] ?? null;
  return typeof first === 'string' ? first : null;
}

function isNetworkError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false;
  const code = (error as { code?: string }).code;
  const message = (error as { message?: string }).message ?? '';
  return code === 'ERR_NETWORK' || code === 'ECONNABORTED' || /network|timeout/i.test(message);
}
