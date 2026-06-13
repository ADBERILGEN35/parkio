import { type ParkioApiError, isParkioApiError } from '@parkio/api-client';
import { ErrorMessage } from '@parkio/ui';

/**
 * Maps a recognised {@link ParkioApiError} to user-friendly wording. Return
 * `null` to fall through to the raw backend message (still shown with its code
 * and traceId).
 */
export type ApiErrorMapper = (error: ParkioApiError) => string | null;

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
  if (isParkioApiError(error)) {
    const friendly = mapper ? mapper(error) : null;
    return (
      <ErrorMessage
        message={friendly ?? error.message}
        code={error.code}
        traceId={error.traceId || undefined}
      />
    );
  }
  return <ErrorMessage message={fallback} />;
}
