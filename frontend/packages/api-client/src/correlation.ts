import { randomUUID } from './uuid';

/** Canonical correlation header — echoed by gateway and services as ApiError.traceId. */
export const CORRELATION_HEADER = 'X-Correlation-Id';

export function createCorrelationId(): string {
  return randomUUID();
}
