/** Canonical correlation header — echoed by gateway and services as ApiError.traceId. */
export const CORRELATION_HEADER = 'X-Correlation-Id';

export function createCorrelationId(): string {
  return crypto.randomUUID();
}
