import { ApiErrorAlert, type ApiErrorMapper } from './ApiErrorAlert';

export type { ApiErrorMapper };

export interface FriendlyApiErrorMessageProps {
  error: unknown;
  /** Optional friendly-wording mapper for expected, page-specific failures. */
  mapper?: ApiErrorMapper;
  /** Message shown when the error is not a ParkioApiError (network/unknown). */
  fallback?: string;
}

/**
 * Single error renderer for the web app. For a ParkioApiError it shows the
 * mapped friendly message (or the raw message when the mapper returns null)
 * together with the error code and traceId; otherwise it shows `fallback`.
 */
export function FriendlyApiErrorMessage({
  error,
  mapper,
  fallback = 'Something went wrong. Please try again.',
}: FriendlyApiErrorMessageProps) {
  return <ApiErrorAlert error={error} mapper={mapper} fallback={fallback} />;
}
