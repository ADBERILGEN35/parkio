import { isParkioApiError } from '@parkio/api-client';
import { ErrorMessage } from '@parkio/ui';

export interface ApiErrorMessageProps {
  error: unknown;
  fallback?: string;
}

/** Renders a ParkioApiError with its code and traceId, or a generic fallback. */
export function ApiErrorMessage({
  error,
  fallback = 'Something went wrong. Please try again.',
}: ApiErrorMessageProps) {
  if (isParkioApiError(error)) {
    return (
      <ErrorMessage message={error.message} code={error.code} traceId={error.traceId || undefined} />
    );
  }
  return <ErrorMessage message={fallback} />;
}
