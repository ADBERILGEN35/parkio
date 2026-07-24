import { randomUUID } from './uuid';

/** Canonical correlation header — echoed by gateway and services as ApiError.traceId. */
export const CORRELATION_HEADER = 'X-Correlation-Id';

declare const correlationIdBrand: unique symbol;
declare const requestIdBrand: unique symbol;

/** One network attempt's backend correlation identifier. */
export type CorrelationId = string & { readonly [correlationIdBrand]: 'CorrelationId' };

/** One logical SDK operation identifier, stable across retry attempts. */
export type RequestId = string & { readonly [requestIdBrand]: 'RequestId' };

export function createCorrelationId(): CorrelationId {
  return randomUUID() as CorrelationId;
}

export function createRequestId(): RequestId {
  return randomUUID() as RequestId;
}
