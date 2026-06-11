/** Header name required by backend write endpoints for safe retries. */
export const IDEMPOTENCY_HEADER = 'Idempotency-Key';

/** Generates a fresh UUID v4 for idempotent write requests. */
export function createIdempotencyKey(): string {
  return crypto.randomUUID();
}
