import { describe, expect, expectTypeOf, it, vi } from 'vitest';
import { NetworkError, serializeParkioError } from './sdk-errors';
import type {
  LoggerPort,
  MetricsPort,
  ObservabilityPorts,
  TraceSpanPort,
  TracerPort,
} from './observability';

const context = {
  requestId: 'request-1',
  correlationId: 'correlation-1',
  operation: 'parkingSession.start',
  attempt: 1,
} as const;

describe('observability ports', () => {
  it('accepts structured, privacy-bounded log and metric events', () => {
    const log = vi.fn();
    const increment = vi.fn();
    const recordDuration = vi.fn();
    const logger: LoggerPort = { log };
    const metrics: MetricsPort = { increment, recordDuration };

    logger.log({
      level: 'warn',
      message: 'request failed',
      context,
      attributes: { outcome: 'network_error', retryable: true, attempt: 1 },
      error: serializeParkioError(new NetworkError('offline', context)),
    });
    metrics.increment({ name: 'sdk.request.failed', context, attributes: { reason: 'network' } });
    metrics.recordDuration({ name: 'sdk.request.duration', durationMs: 125, context });

    expect(log).toHaveBeenCalledOnce();
    expect(increment).toHaveBeenCalledOnce();
    expect(recordDuration).toHaveBeenCalledOnce();
  });

  it('defines tracing lifecycle without binding an exporter', () => {
    const setAttribute = vi.fn();
    const setStatus = vi.fn();
    const recordError = vi.fn();
    const end = vi.fn();
    const span: TraceSpanPort = { setAttribute, setStatus, recordError, end };
    const startSpan = vi.fn(() => span);
    const tracer: TracerPort = { startSpan };

    const activeSpan = tracer.startSpan('parkingSession.start', { context });
    activeSpan.setAttribute('attempt', 1);
    activeSpan.setStatus('ok');
    activeSpan.end();

    expect(startSpan).toHaveBeenCalledWith('parkingSession.start', { context });
    expect(setAttribute).toHaveBeenCalledWith('attempt', 1);
    expect(setStatus).toHaveBeenCalledWith('ok');
    expect(end).toHaveBeenCalledOnce();
  });

  it('keeps every observability dependency optional at composition time', () => {
    expectTypeOf<ObservabilityPorts>().toMatchTypeOf<{
      readonly logger?: LoggerPort;
      readonly metrics?: MetricsPort;
      readonly tracer?: TracerPort;
    }>();
  });
});
