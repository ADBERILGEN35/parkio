import type { AnalyticsTransport, CapturedAnalyticsEvent } from './types';

export interface PostHogCaptureConfig {
  apiKey: string;
  /** e.g. https://eu.i.posthog.com or https://us.i.posthog.com */
  host: string;
  /** Injected fetch for tests. */
  fetchImpl?: typeof fetch;
  /** Max events per batch request. */
  batchSize?: number;
}

/**
 * Narrow PostHog HTTP capture adapter.
 *
 * Deliberately does NOT load posthog-js / posthog-react-native so that:
 * - autocapture cannot activate (SDK default on web is on);
 * - session replay cannot activate (requires separate SDK/plugin);
 * - only explicit events from the approved inventory are sent.
 *
 * Real vendor ingestion requires apiKey + host + consent + vendorEnabled.
 * Without those, this transport must not be installed.
 */
export class PostHogHttpTransport implements AnalyticsTransport {
  private readonly apiKey: string;
  private readonly host: string;
  private readonly fetchImpl: typeof fetch;
  private readonly batchSize: number;
  private distinctId: string | null = null;
  private disposed = false;

  constructor(config: PostHogCaptureConfig) {
    this.apiKey = config.apiKey;
    this.host = config.host.replace(/\/+$/, '');
    this.fetchImpl = config.fetchImpl ?? fetch.bind(globalThis);
    this.batchSize = config.batchSize ?? 20;
  }

  identify(distinctId: string): void {
    this.distinctId = distinctId;
  }

  reset(): void {
    this.distinctId = null;
  }

  dispose(): void {
    this.disposed = true;
  }

  async send(events: CapturedAnalyticsEvent[]): Promise<void> {
    if (this.disposed || events.length === 0) return;
    const endpoint = `${this.host}/batch/`;
    for (let i = 0; i < events.length; i += this.batchSize) {
      const slice = events.slice(i, i + this.batchSize);
      const body = {
        api_key: this.apiKey,
        batch: slice.map((event) => ({
          event: event.name,
          distinct_id: event.distinctId ?? event.analyticsSessionId,
          timestamp: new Date(event.occurredAtMs).toISOString(),
          properties: {
            ...(event.params ?? {}),
            $lib: 'parkio-product-analytics',
            $lib_version: '1.0.0-rc1',
            analytics_session_id: event.analyticsSessionId,
            deferred: event.deferred === true,
            // Explicitly disable PostHog feature flags / replay association
            $session_recording_enabled: false,
            $autocapture_disabled: true,
          },
        })),
      };
      try {
        await this.fetchImpl(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          keepalive: true,
        });
      } catch {
        // fail-open — caller retains queue responsibility
        throw new Error('posthog_batch_failed');
      }
    }
  }
}
