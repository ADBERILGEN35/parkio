import { type ParkioApiError, isParkioApiError } from '@parkio/api-client';
import { ErrorMessage } from '@parkio/ui';

export type ApiErrorMapper = (error: ParkioApiError) => string | null;

export interface ApiErrorAlertProps {
  error: unknown;
  mapper?: ApiErrorMapper;
  fallback?: string;
}

function defaultApiMessage(error: ParkioApiError): string {
  if (error.status === 401) return 'Your session has expired. Please sign in again.';
  if (error.status === 403) return 'You do not have permission to perform this action.';
  if (error.status === 404) return 'We could not find that resource.';
  if (error.status === 409) return error.message;
  if (error.status === 422 || error.status === 400) return error.message;
  if (error.status === 429) return 'Too many attempts. Please wait a moment and try again.';
  if (error.status >= 500) return 'Service is temporarily unavailable. Please try again.';
  return error.message || 'Something went wrong. Please try again.';
}

export function describeApiError(error: unknown, mapper?: ApiErrorMapper, fallback = 'Something went wrong. Please try again.') {
  if (isParkioApiError(error)) {
    return {
      message: mapper?.(error) ?? defaultApiMessage(error),
      code: error.code,
      traceId: error.traceId || undefined,
    };
  }
  return { message: fallback };
}

export function ApiErrorAlert({
  error,
  mapper,
  fallback = 'Something went wrong. Please try again.',
}: ApiErrorAlertProps) {
  const described = describeApiError(error, mapper, fallback);
  return <ErrorMessage {...described} />;
}
