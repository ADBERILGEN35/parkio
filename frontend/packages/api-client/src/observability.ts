import type {
  SdkOperationContext,
  TelemetryAttributes,
  TelemetryAttributeValue,
} from './core-contracts';
import type { SerializedParkioError } from './sdk-errors';

export type SdkLogLevel = 'debug' | 'info' | 'warn' | 'error';

/** A privacy-safe structured log event emitted by the SDK. */
export interface SdkLogEntry {
  readonly level: SdkLogLevel;
  readonly message: string;
  readonly context?: SdkOperationContext;
  readonly attributes?: TelemetryAttributes;
  readonly error?: SerializedParkioError;
}

/** Logging port. Platform adapters own formatting, filtering, and delivery. */
export interface LoggerPort {
  log(entry: SdkLogEntry): void;
}

export interface SdkCounterMeasurement {
  readonly name: string;
  readonly value?: number;
  readonly context?: SdkOperationContext;
  readonly attributes?: TelemetryAttributes;
}

export interface SdkDurationMeasurement {
  readonly name: string;
  readonly durationMs: number;
  readonly context?: SdkOperationContext;
  readonly attributes?: TelemetryAttributes;
}

/** Metrics port. Attribute cardinality and export policy belong to the adapter. */
export interface MetricsPort {
  increment(measurement: SdkCounterMeasurement): void;
  recordDuration(measurement: SdkDurationMeasurement): void;
}

export type SdkSpanStatus = 'unset' | 'ok' | 'error';

export interface SdkSpanOptions {
  readonly context?: SdkOperationContext;
  readonly attributes?: TelemetryAttributes;
}

/** Mutable handle for one trace span; implementations MUST make end idempotent. */
export interface TraceSpanPort {
  setAttribute(name: string, value: TelemetryAttributeValue): void;
  setStatus(status: SdkSpanStatus): void;
  recordError(error: SerializedParkioError): void;
  end(): void;
}

/** Tracing port. Trace propagation and exporter ownership remain platform concerns. */
export interface TracerPort {
  startSpan(name: string, options?: SdkSpanOptions): TraceSpanPort;
}

/** Optional observability dependencies accepted by later SDK composition work. */
export interface ObservabilityPorts {
  readonly logger?: LoggerPort;
  readonly metrics?: MetricsPort;
  readonly tracer?: TracerPort;
}
