import axios, { type AxiosError, isAxiosError } from 'axios';
import {
  CancellationError,
  NetworkError,
  TimeoutError,
  UnknownSdkError,
  toParkioError,
} from './sdk-errors';

export * from './sdk-errors';

function isCanceledLike(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const candidate = error as { code?: string; name?: string };
  return (
    candidate.code === 'ERR_CANCELED' ||
    candidate.name === 'CanceledError' ||
    candidate.name === 'AbortError'
  );
}

/**
 * Axios-specific compatibility adapter for the existing client.
 * Maps cancel / timeout / offline failures onto the typed SDK hierarchy so
 * Query retry policy does not treat aborts as retryable 5xx.
 */
export function getAxiosParkioError(error: unknown) {
  if (axios.isCancel(error) || isCanceledLike(error)) {
    const message =
      error instanceof Error && error.message ? error.message : 'Request was cancelled.';
    return new CancellationError(message, { cause: error });
  }

  if (!isAxiosError(error)) {
    return new UnknownSdkError(
      error instanceof Error ? error.message : 'An unexpected error occurred.',
      { cause: error },
    );
  }

  const axiosError: AxiosError = error;

  if (axiosError.code === 'ECONNABORTED') {
    return new TimeoutError(axiosError.message || 'Request timed out.', {
      cause: axiosError,
      timeoutMs:
        typeof axiosError.config?.timeout === 'number' ? axiosError.config.timeout : undefined,
    });
  }

  if (!axiosError.response) {
    return new NetworkError(axiosError.message || 'Network request failed.', {
      cause: axiosError,
    });
  }

  return toParkioError(axiosError.response.status, axiosError.response.data, {
    cause: axiosError,
  });
}
