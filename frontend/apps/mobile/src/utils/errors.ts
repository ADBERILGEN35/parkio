import { isParkioApiError, ParkioApiError } from '@parkio/api-client';

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
    return 'You appear to be offline. Check your connection and try again.';
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Something went wrong. Please try again.';
}

function messageForApiError(error: ParkioApiError): string {
  switch (error.status) {
    case 400:
    case 422:
      return firstFieldError(error) ?? 'Please check the details and try again.';
    case 401:
      return 'Your session has expired. Please sign in again.';
    case 403:
      return 'You don’t have access to do that.';
    case 404:
      return 'We couldn’t find what you were looking for.';
    case 409:
      return error.message || 'That conflicts with something that already exists.';
    case 429:
      return 'Too many attempts. Please wait a moment and try again.';
    default:
      return error.status >= 500
        ? 'Our servers are having a moment. Please try again shortly.'
        : error.message || 'Something went wrong. Please try again.';
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
