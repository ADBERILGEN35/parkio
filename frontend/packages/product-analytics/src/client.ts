import {
  CLIENT_ANALYTICS_SCHEMA_VERSION,
  type SpaTelemetryEventName,
  type SpaTelemetryParams,
} from '@parkio/types';
import { sanitizeSpaTelemetryParams } from '@parkio/validation';
import { ActiveTimeTracker } from './activeTime';
import { LocalCaptureTransport, NullTransport } from './localCapture';
import { normalizeAnalyticsScreenName } from './normalizeScreen';
import { PostHogHttpTransport } from './posthogHttp';
import type {
  AnalyticsConsentState,
  AnalyticsStorage,
  AnalyticsTransport,
  CapturedAnalyticsEvent,
  ProductAnalyticsConfig,
  ScreenActiveTimeSnapshot,
} from './types';

const CONSENT_KEY = 'parkio.analytics.consent.v1';
const SESSION_KEY = 'parkio.analytics.session.v1';
const DISTINCT_KEY = 'parkio.analytics.distinct.v1';

function createId(prefix: string): string {
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  return `${prefix}_${Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')}`;
}

/**
 * Product analytics client implementing client.analytics.v1 activation rules:
 * - Disabled by default (consent unset/denied → no outbound).
 * - Opt-out discards queue; enabling later does not upload pre-consent history.
 * - Logout clears distinct id + session; never rebinds User A queue to User B.
 * - Queued events retain occurredAtMs (not receipt time).
 */
export class ProductAnalyticsClient {
  private readonly config: ProductAnalyticsConfig;
  private readonly storage: AnalyticsStorage;
  private readonly maxQueueSize: number;
  private readonly wallNow: () => number;
  private transport: AnalyticsTransport;
  private localCapture: LocalCaptureTransport | null = null;
  private consent: AnalyticsConsentState = 'unset';
  private analyticsSessionId: string;
  private distinctId: string | null = null;
  private queue: CapturedAnalyticsEvent[] = [];
  private seq = 0;
  private previousScreenName: ReturnType<typeof normalizeAnalyticsScreenName> | null = null;
  private readonly activeTime: ActiveTimeTracker;
  private dropCount = 0;
  private initialized = false;
  /** When true, LocalCapture mirrors every granted event even if vendor is null. */
  private mirrorLocal = false;

  constructor(config: ProductAnalyticsConfig) {
    this.config = config;
    this.storage = config.storage;
    this.maxQueueSize = config.maxQueueSize ?? 100;
    this.wallNow = config.now ?? (() => Date.now());
    this.analyticsSessionId = createId('as');
    this.transport = new NullTransport();
    this.activeTime = new ActiveTimeTracker({
      idleTimeoutMs: config.idleTimeoutMs ?? 60_000,
      heartbeatIntervalMs: config.heartbeatIntervalMs ?? 0,
      monotonicNow: config.monotonicNow,
      onHeartbeat:
        (config.heartbeatIntervalMs ?? 0) > 0
          ? (snap) => {
              this.track('screen_engagement_summary', {
                screenName: snap.screenName,
                activeDurationMs: snap.activeDurationMs,
                incomplete: snap.incomplete,
                checkpointSeq: snap.checkpointSeq,
              });
            }
          : undefined,
    });
  }

  async init(): Promise<void> {
    if (this.initialized) return;
    this.initialized = true;
    const storedConsent = await this.storage.getItem(CONSENT_KEY);
    if (storedConsent === 'granted' || storedConsent === 'denied') {
      this.consent = storedConsent;
    }
    const storedSession = await this.storage.getItem(SESSION_KEY);
    if (storedSession) this.analyticsSessionId = storedSession;
    else await this.storage.setItem(SESSION_KEY, this.analyticsSessionId);

    const storedDistinct = await this.storage.getItem(DISTINCT_KEY);
    if (storedDistinct && this.consent === 'granted') {
      this.distinctId = storedDistinct;
    } else if (storedDistinct) {
      await this.storage.removeItem(DISTINCT_KEY);
    }

    this.rebuildTransport();
    if (this.consent === 'granted') {
      this.track('analytics_session_started', { schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION });
    }
  }

  /** Wire LocalCaptureTransport for isolated acceptance (never network). */
  useLocalCapture(): LocalCaptureTransport {
    this.localCapture = new LocalCaptureTransport();
    this.mirrorLocal = true;
    this.rebuildTransport();
    return this.localCapture;
  }

  getLocalCapture(): LocalCaptureTransport | null {
    return this.localCapture;
  }

  getConsent(): AnalyticsConsentState {
    return this.consent;
  }

  getDropCount(): number {
    return this.dropCount;
  }

  getQueuedForTests(): readonly CapturedAnalyticsEvent[] {
    return this.queue;
  }

  getAnalyticsSessionId(): string {
    return this.analyticsSessionId;
  }

  getDistinctId(): string | null {
    return this.distinctId;
  }

  async setConsent(next: AnalyticsConsentState): Promise<void> {
    const prev = this.consent;

    if (next === 'denied' || next === 'unset') {
      // Discard unsent queue before flipping consent so nothing flushes.
      this.queue.length = 0;
      this.consent = next;
      await this.storage.setItem(CONSENT_KEY, next);
      this.distinctId = null;
      await this.storage.removeItem(DISTINCT_KEY);
      this.rebuildTransport();
      this.transport.reset?.();
      if (prev === 'granted' && this.localCapture && !this.localCapture.disposed) {
        // Local-only marker for acceptance (not vendor outbound).
        this.localCapture.send([
          {
            name: 'analytics_session_ended',
            params: {
              sessionEndReason: 'opt_out',
              schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION,
              platform: this.config.platform,
            },
            occurredAtMs: this.wallNow(),
            seq: ++this.seq,
            analyticsSessionId: this.analyticsSessionId,
          },
        ]);
      }
      return;
    }

    // granted: start fresh — do NOT upload events captured before consent.
    this.queue.length = 0;
    this.consent = 'granted';
    await this.storage.setItem(CONSENT_KEY, 'granted');
    this.analyticsSessionId = createId('as');
    await this.storage.setItem(SESSION_KEY, this.analyticsSessionId);
    this.rebuildTransport();
    this.track('analytics_session_started', { schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION });
  }

  /**
   * Associate a backend-provided pseudonymous id (never email/raw userId).
   * No-op when consent is not granted. Does not alias anonymous history.
   */
  async identify(pseudonymousDistinctId: string): Promise<void> {
    if (this.consent !== 'granted') return;
    const id = pseudonymousDistinctId.trim();
    if (!id || id.includes('@') || id.length > 128) return;
    this.distinctId = id;
    await this.storage.setItem(DISTINCT_KEY, id);
    this.transport.identify?.(id);
  }

  /**
   * Logout / account switch: end session, clear distinct, rotate analytics session.
   * Queued events for the prior identity are discarded (never reassigned).
   */
  async resetIdentity(): Promise<void> {
    if (this.consent === 'granted') {
      const snap = this.activeTime.exitScreen({ incomplete: false });
      if (snap) this.emitEngagement(snap);
      this.track('analytics_session_ended', {
        sessionEndReason: 'logout',
        schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION,
      });
    }
    this.queue.length = 0;
    this.distinctId = null;
    await this.storage.removeItem(DISTINCT_KEY);
    this.analyticsSessionId = createId('as');
    await this.storage.setItem(SESSION_KEY, this.analyticsSessionId);
    this.transport.reset?.();
    this.previousScreenName = null;
  }

  track(
    name: SpaTelemetryEventName | string,
    params?: SpaTelemetryParams,
    options?: { strict?: boolean },
  ): void {
    if (this.consent !== 'granted') return;

    let safe: SpaTelemetryParams | undefined;
    try {
      safe = sanitizeSpaTelemetryParams({
        platform: this.config.platform,
        appVersion: this.config.appVersion,
        schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION,
        ...params,
      });
    } catch (error) {
      if (options?.strict) throw error;
      this.dropCount += 1;
      return;
    }

    const event: CapturedAnalyticsEvent = {
      name,
      params: safe,
      occurredAtMs: this.wallNow(),
      seq: ++this.seq,
      analyticsSessionId: this.analyticsSessionId,
      distinctId: this.distinctId ?? undefined,
    };

    if (this.mirrorLocal && this.localCapture && !this.localCapture.disposed) {
      this.localCapture.send([event]);
    }

    void this.dispatch(event);
  }

  trackScreenViewed(pathOrRoute: string): void {
    const screenName = normalizeAnalyticsScreenName(pathOrRoute);
    const previous = this.activeTime.enterScreen(screenName);
    if (previous) this.emitEngagement(previous);
    this.track('screen_viewed', {
      screenName,
      previousScreenName: this.previousScreenName ?? undefined,
      schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION,
    });
    this.previousScreenName = screenName;
  }

  noteInteraction(): void {
    this.activeTime.noteInteraction();
  }

  setForeground(active: boolean): void {
    this.activeTime.setForeground(active);
  }

  setFocused(focused: boolean): void {
    this.activeTime.setFocused(focused);
  }

  flushActiveScreen(incomplete = false): void {
    const snap = this.activeTime.exitScreen({ incomplete });
    if (snap) this.emitEngagement(snap);
  }

  applyOutOfOrderCheckpoint(seq: number, activeDurationMs: number): void {
    this.activeTime.applyCheckpoint(seq, activeDurationMs);
  }

  async flush(): Promise<void> {
    if (this.consent !== 'granted' || this.queue.length === 0) return;
    const batch = this.queue.splice(0);
    for (const event of batch) {
      event.deferred = true;
    }
    try {
      await this.transport.send(batch);
      if (this.mirrorLocal && this.localCapture && !this.localCapture.disposed) {
        // already mirrored at capture time
      }
    } catch {
      for (const event of batch) {
        this.enqueue(event);
      }
    }
  }

  dispose(): void {
    this.activeTime.dispose();
    this.transport.dispose?.();
    this.queue.length = 0;
  }

  private emitEngagement(snap: ScreenActiveTimeSnapshot): void {
    this.track('screen_engagement_summary', {
      screenName: snap.screenName,
      activeDurationMs: snap.activeDurationMs,
      incomplete: snap.incomplete,
      checkpointSeq: snap.checkpointSeq,
      schemaVersion: CLIENT_ANALYTICS_SCHEMA_VERSION,
    });
  }

  private async dispatch(event: CapturedAnalyticsEvent): Promise<void> {
    try {
      await this.transport.send([event]);
    } catch {
      event.deferred = true;
      this.enqueue(event);
    }
  }

  private enqueue(event: CapturedAnalyticsEvent): void {
    this.queue.push(event);
    while (this.queue.length > this.maxQueueSize) {
      this.queue.shift();
      this.dropCount += 1;
    }
  }

  private rebuildTransport(): void {
    // Never dispose the local capture mirror — only dispose vendor transports.
    if (this.transport !== this.localCapture) {
      this.transport.dispose?.();
    }

    const host = this.config.posthog?.host ?? '';
    const blockedHost =
      host.length === 0 ||
      /example\.invalid|analytics\.test|0\.0\.0\.0/i.test(host);

    const canVendor =
      this.consent === 'granted' &&
      this.config.vendorEnabled === true &&
      !!this.config.posthog?.apiKey &&
      !blockedHost;

    if (canVendor && this.config.posthog) {
      this.transport = new PostHogHttpTransport({
        apiKey: this.config.posthog.apiKey,
        host: this.config.posthog.host,
      });
      if (this.distinctId) this.transport.identify?.(this.distinctId);
      return;
    }

    // Default outbound is null. Local capture remains a separate mirror.
    this.transport = new NullTransport();
  }
}

export function createMemoryStorage(): AnalyticsStorage {
  const map = new Map<string, string>();
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
    removeItem: (key) => {
      map.delete(key);
    },
  };
}

export function createWebLocalStorage(): AnalyticsStorage {
  return {
    getItem: (key) => {
      try {
        return globalThis.localStorage?.getItem(key) ?? null;
      } catch {
        return null;
      }
    },
    setItem: (key, value) => {
      try {
        globalThis.localStorage?.setItem(key, value);
      } catch {
        // fail-open
      }
    },
    removeItem: (key) => {
      try {
        globalThis.localStorage?.removeItem(key);
      } catch {
        // fail-open
      }
    },
  };
}
