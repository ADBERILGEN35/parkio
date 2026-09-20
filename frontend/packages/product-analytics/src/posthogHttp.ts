import type { AnalyticsTransport, CapturedAnalyticsEvent } from './types';

export interface PostHogCaptureConfig {
  /**
   * PostHog **project** API token (`phc_…`), never a personal/management key.
   * Personal keys (`phx_…`) are rejected.
   */
  apiKey: string;
  /** e.g. https://eu.i.posthog.com or https://us.i.posthog.com */
  host: string;
  /** Injected fetch for tests. */
  fetchImpl?: typeof fetch;
  /** Max events per batch request (default 20). */
  batchSize?: number;
  /** Request timeout ms (default 8_000). */
  timeoutMs?: number;
  /** Soft body size limit bytes (default 512 KiB; PostHog hard limit ~20MB). */
  maxBodyBytes?: number;
}

export type PostHogSendFailureKind =
  | 'network'
  | 'timeout'
  | 'http_4xx'
  | 'http_429'
  | 'http_5xx'
  | 'payload';

export class PostHogSendError extends Error {
  readonly kind: PostHogSendFailureKind;
  readonly status?: number;
  /** Parsed Retry-After delay in ms when kind is http_429 (if header present). */
  readonly retryAfterMs?: number;

  constructor(
    kind: PostHogSendFailureKind,
    message: string,
    status?: number,
    retryAfterMs?: number,
  ) {
    super(message);
    this.name = 'PostHogSendError';
    this.kind = kind;
    this.status = status;
    this.retryAfterMs = retryAfterMs;
  }
}

/** Parse Retry-After as delta-seconds or HTTP-date. Returns undefined when absent/invalid. */
export function parseRetryAfterMs(
  header: string | null,
  nowMs: number = Date.now(),
): number | undefined {
  if (!header) return undefined;
  const trimmed = header.trim();
  if (/^\d+$/.test(trimmed)) {
    const seconds = Number(trimmed);
    if (!Number.isFinite(seconds) || seconds < 0) return undefined;
    return Math.min(seconds * 1000, 600_000);
  }
  const when = Date.parse(trimmed);
  if (Number.isNaN(when)) return undefined;
  return Math.min(Math.max(0, when - nowMs), 600_000);
}

/** True when key looks like a PostHog project ingestion token. */
export function isPostHogProjectApiKey(apiKey: string): boolean {
  return /^phc_[A-Za-z0-9]+$/.test(apiKey.trim());
}

function createEventUuid(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Narrow PostHog HTTP `/batch/` capture adapter (protocol-faithful).
 *
 * Official contract (posthog.com/docs/api/capture):
 * - POST `{host}/batch/` with `{ api_key, batch: [{ event, distinct_id, timestamp?, properties? }] }`
 * - `api_key` = project token (`phc_…`), not personal management credentials
 * - `timestamp` must be ISO 8601 (epoch numbers are treated as ingestion time)
 * - Missing/empty `distinct_id` → event silently dropped server-side (still HTTP 200)
 * - Anonymous: set `$process_person_profile: false`
 * - Body must stay under ~20MB
 *
 * Deliberately does NOT load posthog-js / posthog-react-native:
 * no autocapture, no session replay plugin surface.
 *
 * Mock success ≠ real PostHog ingestion, vendor dedupe, or production CORS.
 */
export class PostHogHttpTransport implements AnalyticsTransport {
  private readonly apiKey: string;
  private readonly host: string;
  private readonly fetchImpl: typeof fetch;
  private readonly batchSize: number;
  private readonly timeoutMs: number;
  private readonly maxBodyBytes: number;
  /** Fallback distinct id after identify(); cleared on reset. */
  private identifiedDistinctId: string | null = null;
  private disposed = false;

  constructor(config: PostHogCaptureConfig) {
    if (!isPostHogProjectApiKey(config.apiKey)) {
      throw new Error('posthog_invalid_project_api_key');
    }
    this.apiKey = config.apiKey.trim();
    this.host = config.host.replace(/\/+$/, '');
    this.fetchImpl = config.fetchImpl ?? fetch.bind(globalThis);
    this.batchSize = config.batchSize ?? 20;
    this.timeoutMs = config.timeoutMs ?? 8_000;
    this.maxBodyBytes = config.maxBodyBytes ?? 512 * 1024;
  }

  identify(distinctId: string): void {
    const id = distinctId.trim().slice(0, 200);
    if (!id) return;
    this.identifiedDistinctId = id;
  }

  reset(): void {
    this.identifiedDistinctId = null;
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
        historical_migration: false,
        batch: slice.map((event) => {
          const distinctId = (
            event.distinctId ??
            this.identifiedDistinctId ??
            event.analyticsSessionId
          )
            .trim()
            .slice(0, 200);
          const identified = !!event.distinctId || !!this.identifiedDistinctId;
          return {
            event: event.name,
            // Top-level distinct_id wins per PostHog docs.
            distinct_id: distinctId,
            uuid: createEventUuid(),
            timestamp: new Date(event.occurredAtMs).toISOString(),
            properties: {
              ...(event.params ?? {}),
              $lib: 'parkio-product-analytics',
              $lib_version: '1.0.0-rc1',
              analytics_session_id: event.analyticsSessionId,
              deferred: event.deferred === true,
              // Anonymous sessions must not create person profiles.
              $process_person_profile: identified,
              $session_recording_enabled: false,
              $autocapture_disabled: true,
            },
          };
        }),
      };

      const serialized = JSON.stringify(body);
      if (serialized.length > this.maxBodyBytes) {
        throw new PostHogSendError('payload', 'posthog_batch_too_large');
      }

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), this.timeoutMs);
      try {
        const response = await this.fetchImpl(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: serialized,
          keepalive: true,
          signal: controller.signal,
        });
        if (!response.ok) {
          if (response.status === 429) {
            const retryAfterMs = parseRetryAfterMs(response.headers.get('Retry-After'));
            // Rate limit is retryable — never treated as permanent 4xx drop.
            throw new PostHogSendError(
              'http_429',
              'posthog_http_429',
              429,
              retryAfterMs,
            );
          }
          const kind: PostHogSendFailureKind =
            response.status >= 500 ? 'http_5xx' : 'http_4xx';
          // Permanent client errors (auth/payload rejection): caller drops.
          // Ambiguous after network/timeout: caller requeues (possible duplicate).
          throw new PostHogSendError(kind, `posthog_http_${response.status}`, response.status);
        }
        // Note: PostHog may return 200 even when individual events lack distinct_id
        // and are silently dropped. We always send distinct_id to avoid that hole.
      } catch (error) {
        if (error instanceof PostHogSendError) throw error;
        if (error instanceof Error && error.name === 'AbortError') {
          throw new PostHogSendError('timeout', 'posthog_timeout');
        }
        // Delivery may already have been accepted server-side — requeue can duplicate.
        throw new PostHogSendError('network', 'posthog_batch_failed');
      } finally {
        clearTimeout(timer);
      }
    }
  }
}
