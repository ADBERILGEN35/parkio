import type { CorrelationId, RequestId } from './correlation';

/** Primitive values permitted in structured SDK telemetry. */
export type TelemetryAttributeValue = string | number | boolean | null;

/**
 * Bounded, structured telemetry attributes. Callers MUST NOT include tokens,
 * raw idempotency keys, precise coordinates, or user-provided payloads.
 */
export type TelemetryAttributes = Readonly<Record<string, TelemetryAttributeValue>>;

/** Identifies one logical SDK operation across retries and network attempts. */
export interface SdkOperationContext {
  readonly requestId: RequestId;
  readonly operation: string;
  readonly attempt: number;
  readonly correlationId?: CorrelationId;
}
