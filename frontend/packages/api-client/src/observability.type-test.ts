import type {
  ApiErrorMappingOptions,
  CorrelationId,
  LoggerPort,
  MetricsPort,
  ObservabilityPorts,
  ParkioApiErrorOptions,
  RequestId,
  SdkErrorContext,
  SdkErrorOptions,
  SdkLogEntry,
  SdkOperationContext,
  SerializedParkioError,
  SerializedParkioErrorName,
  TimeoutErrorOptions,
  TraceSpanPort,
  TracerPort,
} from './index';

declare const requestId: RequestId;
declare const correlationId: CorrelationId;

const context: SdkOperationContext = {
  requestId,
  correlationId,
  operation: 'parkingSession.start',
  attempt: 1,
};

const logger: LoggerPort = {
  log(entry) {
    void entry;
  },
};

const metrics: MetricsPort = {
  increment(measurement) {
    void measurement;
  },
  recordDuration(measurement) {
    void measurement;
  },
};

const span: TraceSpanPort = {
  setAttribute(name, value) {
    void name;
    void value;
  },
  setStatus(status) {
    void status;
  },
  recordError(error) {
    void error;
  },
  end() {},
};

const tracer: TracerPort = {
  startSpan(name, options) {
    void name;
    void options;
    return span;
  },
};

export const observabilityTypeContract = {
  logger,
  metrics,
  tracer,
} satisfies ObservabilityPorts;

export const validLogEntry = {
  level: 'error',
  message: 'request failed',
  context,
} satisfies SdkLogEntry;

export const requestIdWireValue: string = requestId;
export const correlationIdWireValue: string = correlationId;

export interface PublicErrorSupportTypes {
  readonly apiMapping: ApiErrorMappingOptions;
  readonly apiOptions: ParkioApiErrorOptions;
  readonly context: SdkErrorContext;
  readonly options: SdkErrorOptions;
  readonly serialized: SerializedParkioError;
  readonly serializedName: SerializedParkioErrorName;
  readonly timeout: TimeoutErrorOptions;
}

// @ts-expect-error RequestId and CorrelationId are intentionally distinct.
export const invalidRequestId: RequestId = correlationId;

// @ts-expect-error RequestId and CorrelationId are intentionally distinct.
export const invalidCorrelationId: CorrelationId = requestId;

// @ts-expect-error Arbitrary strings are not RequestId values.
export const invalidStringRequestId: RequestId = 'request-1';

// @ts-expect-error Log levels are a frozen closed set.
export const invalidLogEntry: SdkLogEntry = { level: 'verbose', message: 'invalid' };
