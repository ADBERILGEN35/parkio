import type { AnalyticsTransport, CapturedAnalyticsEvent } from './types';

/**
 * In-memory capture for isolated acceptance. Never performs network I/O.
 */
export class LocalCaptureTransport implements AnalyticsTransport {
  readonly events: CapturedAnalyticsEvent[] = [];
  identifyCalls: string[] = [];
  resetCount = 0;
  disposed = false;

  send(events: CapturedAnalyticsEvent[]): void {
    if (this.disposed) return;
    this.events.push(...events);
  }

  identify(distinctId: string): void {
    this.identifyCalls.push(distinctId);
  }

  reset(): void {
    this.resetCount += 1;
  }

  dispose(): void {
    this.disposed = true;
  }

  clear(): void {
    this.events.length = 0;
    this.identifyCalls = [];
    this.resetCount = 0;
  }
}

/**
 * Dropping transport used when analytics is disabled — records nothing.
 */
export class NullTransport implements AnalyticsTransport {
  send(): void {
    // no-op
  }
}
