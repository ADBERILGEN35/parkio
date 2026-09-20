import type {
  AnalyticsScreenName,
  SpaTelemetryEventName,
  SpaTelemetryParams,
  SpaTelemetryPlatform,
} from '@parkio/types';

export type AnalyticsConsentState = 'unset' | 'granted' | 'denied';

export interface CapturedAnalyticsEvent {
  name: SpaTelemetryEventName | string;
  params?: SpaTelemetryParams;
  /** Occurrence time (ms since epoch). Retained across offline flush. */
  occurredAtMs: number;
  /** Monotonic capture order within process. */
  seq: number;
  /** Anonymous session id active at capture (never email/userId). */
  analyticsSessionId: string;
  /** Pseudonymous distinct id when identified; omitted for anonymous. */
  distinctId?: string;
  deferred?: boolean;
}

export interface AnalyticsTransport {
  send(events: CapturedAnalyticsEvent[]): Promise<void> | void;
  identify?(distinctId: string): Promise<void> | void;
  reset?(): Promise<void> | void;
  dispose?(): void;
}

export interface AnalyticsStorage {
  getItem(key: string): string | null | Promise<string | null>;
  setItem(key: string, value: string): void | Promise<void>;
  removeItem(key: string): void | Promise<void>;
}

export interface ProductAnalyticsConfig {
  platform: SpaTelemetryPlatform;
  appVersion?: string;
  storage: AnalyticsStorage;
  /** When false (default), never transmit. */
  vendorEnabled?: boolean;
  posthog?: {
    apiKey: string;
    host: string;
  };
  /** Max events held while offline / before opt-in flush. */
  maxQueueSize?: number;
  /** Idle stop after this many ms without interaction (default 60_000). */
  idleTimeoutMs?: number;
  /**
   * Optional checkpoint interval while active. Prefer local accumulation;
   * default is 0 (disabled) — summaries emit on screen exit only.
   */
  heartbeatIntervalMs?: number;
  now?: () => number;
  monotonicNow?: () => number;
}

export interface ScreenActiveTimeSnapshot {
  screenName: AnalyticsScreenName;
  activeDurationMs: number;
  incomplete: boolean;
  checkpointSeq: number;
}

export type { AnalyticsScreenName, SpaTelemetryEventName, SpaTelemetryParams };
