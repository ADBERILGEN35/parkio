import { RateLimitError } from '@parkio/api-client';

/** True when the public geocoding endpoint rate-limited the client. */
export function isPublicSearchRateLimited(error: unknown): boolean {
  return error instanceof RateLimitError;
}
